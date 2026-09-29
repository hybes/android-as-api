package dev.phoneapi

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteDatabase
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class EventStore(context: Context, name: String? = "events.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY, payload TEXT NOT NULL, created INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun add(event: JSONObject) {
        writableDatabase.insertOrThrow("events", null, ContentValues().apply {
            put("id", event.getString("id")); put("payload", event.toString()); put("created", event.getLong("createdAt"))
        })
    }
    fun next(): JSONObject? = readableDatabase.rawQuery("SELECT payload FROM events ORDER BY created, id LIMIT 1", null).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }
    fun remove(id: String) { writableDatabase.delete("events", "id = ?", arrayOf(id)) }
    fun clear() { writableDatabase.delete("events", null, null) }
    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM events", null).use { it.moveToFirst(); it.getInt(0) }
    fun prune(): Int {
        val expired = writableDatabase.delete("events", "created < ?", arrayOf((System.currentTimeMillis() - 7 * 86400_000L).toString()))
        val excess = (count() - 500).coerceAtLeast(0)
        if (excess > 0) writableDatabase.execSQL("DELETE FROM events WHERE id IN (SELECT id FROM events ORDER BY created, id LIMIT ?)", arrayOf(excess))
        return expired + excess
    }
}

object Webhooks {
    private const val JOB = 8765
    val worker = Executors.newSingleThreadExecutor()
    private val kinds = setOf("sms.received", "notification.posted", "notification.removed")
    private fun prefs(context: Context) = context.getSharedPreferences("webhooks", Context.MODE_PRIVATE)
    fun status(context: Context) = synchronized(this) {
        val prefs = prefs(context)
        EventStore(context).use { store ->
            JSONObject().put("enabled", prefs.getBoolean("enabled", false)).put("url", prefs.getString("url", ""))
                .put("hasToken", !prefs.getString("token", "").isNullOrEmpty())
                .put("events", JSONArray(prefs.getString("events", "[\"sms.received\"]")))
                .put("packages", JSONArray(prefs.getString("packages", "[]")))
                .put("pending", store.count()).put("delivered", prefs.getLong("delivered", 0))
                .put("dropped", prefs.getLong("dropped", 0)).put("lastSuccessAt", prefs.getLong("lastSuccessAt", 0))
                .put("lastError", prefs.getString("lastError", ""))
        }
    }
    fun validateUrl(value: String): URI {
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Invalid webhook URL") }
        require(value.length <= 2048 && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
            && (uri.port == -1 || uri.port in 1..65535)) { "Use an http(s) webhook URL without embedded credentials or a fragment" }
        return uri
    }
    // Recheck HTTP destinations at delivery; plain HTTP is allowed only on private networks.
    fun destination(value: String): java.net.URL {
        val uri = validateUrl(value)
        if (uri.scheme == "http") {
            val addresses = InetAddress.getAllByName(uri.host)
            require(addresses.isNotEmpty() && addresses.all { ip ->
                val host = ip.hostAddress.orEmpty()
                val octets = host.split('.')
                ip.isLoopbackAddress || ip.isSiteLocalAddress ||
                    (octets.size == 4 && octets[0] == "100" && (octets[1].toIntOrNull() ?: -1) in 64..127) ||
                    host.startsWith("fd", true) || host.startsWith("fc", true)
            }) { "Use HTTPS for a public webhook destination" }
        }
        return uri.toURL()
    }
    @Synchronized fun configure(context: Context, body: JSONObject): JSONObject {
        require(body.keys().asSequence().all { it in setOf("url", "enabled", "token", "events", "packages") }) { "Unknown webhook field" }
        val old = prefs(context)
        val url = if (body.has("url")) body.get("url") as? String ?: throw IllegalArgumentException("url must be a string") else old.getString("url", "")!!
        val enabled = if (body.has("enabled")) body.get("enabled") as? Boolean ?: throw IllegalArgumentException("enabled must be boolean") else old.getBoolean("enabled", false)
        if (url.isNotEmpty()) validateUrl(url)
        require(!enabled || url.isNotEmpty()) { "Save a webhook URL before enabling delivery" }
        val token = if (body.has("token")) body.get("token") as? String ?: throw IllegalArgumentException("token must be a string")
            else if (url != old.getString("url", "")) "" else old.getString("token", "")!!
        require(token.length <= 512 && token.all { it.code in 33..126 }) { "token must contain at most 512 visible ASCII characters" }
        val events = if (body.has("events")) body.get("events") as? JSONArray ?: throw IllegalArgumentException("events must be an array") else JSONArray(old.getString("events", "[\"sms.received\"]"))
        require(events.length() <= kinds.size && (0 until events.length()).all { events.get(it) is String && events.getString(it) in kinds }) { "Choose sms.received, notification.posted or notification.removed" }
        val packages = if (body.has("packages")) body.get("packages") as? JSONArray ?: throw IllegalArgumentException("packages must be an array") else JSONArray(old.getString("packages", "[]"))
        require(packages.length() <= 50 && (0 until packages.length()).all { packages.get(it) is String && packages.getString(it).matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) }) { "packages must contain up to 50 Android package names" }
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancel(JOB)
        if (!enabled || url != old.getString("url", "")) EventStore(context).use { it.clear() }
        check(old.edit().putString("url", url).putString("token", token).putBoolean("enabled", enabled)
            .putString("events", events.toString()).putString("packages", packages.toString()).remove("lastError").commit())
        if (enabled) schedule(context)
        return status(context)
    }
    @Synchronized fun enqueue(context: Context, kind: String, data: JSONObject, test: Boolean = false): String? {
        val prefs = prefs(context)
        if (!prefs.getBoolean("enabled", false) || !context.getSharedPreferences("api", Context.MODE_PRIVATE).getBoolean("enabled", false)) return null
        val events = JSONArray(prefs.getString("events", "[\"sms.received\"]"))
        if (!test && (0 until events.length()).none { events.getString(it) == kind }) return null
        if (kind.startsWith("notification.")) {
            val packages = JSONArray(prefs.getString("packages", "[]"))
            // Explicit app selection avoids forwarding every notification by default.
            if ((0 until packages.length()).none { packages.getString(it) == data.optString("package") }) return null
        }
        val id = UUID.randomUUID().toString()
        EventStore(context).use { store ->
            store.add(JSONObject().put("id", id).put("type", kind).put("createdAt", System.currentTimeMillis()).put("data", data))
            val dropped = store.prune()
            if (dropped > 0) prefs.edit().putLong("dropped", prefs.getLong("dropped", 0) + dropped).apply()
        }
        schedule(context)
        return id
    }
    @Synchronized fun schedule(context: Context) {
        if (!prefs(context).getBoolean("enabled", false) || !context.getSharedPreferences("api", Context.MODE_PRIVATE).getBoolean("enabled", false)) return
        if (EventStore(context).use { it.count() } == 0) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB) != null) return
        val job = JobInfo.Builder(JOB, ComponentName(context, WebhookJob::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true).setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) prefs(context).edit().putString("lastError", "Android could not schedule delivery").apply()
    }
    fun pause(context: Context) { context.getSystemService(JobScheduler::class.java).cancel(JOB) }
    fun deliverOne(context: Context): Boolean {
        val delivery = synchronized(this) {
            val prefs = prefs(context)
            if (!prefs.getBoolean("enabled", false) || !context.getSharedPreferences("api", Context.MODE_PRIVATE).getBoolean("enabled", false)) return false
            EventStore(context).use { store ->
                val dropped = store.prune()
                if (dropped > 0) prefs.edit().putLong("dropped", prefs.getLong("dropped", 0) + dropped).apply()
                val event = store.next() ?: return false
                Triple(prefs.getString("url", "")!!, prefs.getString("token", "")!!, event)
            }
        }
        val (url, token, event) = delivery
        val conn = destination(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 5000; conn.readTimeout = 5000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Phone-API-Event-ID", event.getString("id"))
            conn.setRequestProperty("X-Phone-API-Event", event.getString("type"))
            if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            val bytes = event.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            if (conn.responseCode !in 200..299) throw FeatureUnavailable("Receiver returned HTTP ${conn.responseCode}")
            return synchronized(this) {
                val prefs = prefs(context)
                EventStore(context).use { store ->
                    if (prefs.getString("url", "") == url) {
                        store.remove(event.getString("id"))
                        prefs.edit().putLong("delivered", prefs.getLong("delivered", 0) + 1).putLong("lastSuccessAt", System.currentTimeMillis()).remove("lastError").apply()
                    }
                    store.count() > 0 && prefs.getBoolean("enabled", false)
                }
            }
        } finally { conn.disconnect() }
    }
    fun failed(context: Context, error: Exception) {
        val message = if (error is FeatureUnavailable || error is IllegalArgumentException) error.message else "Delivery failed; Android will retry"
        prefs(context).edit().putString("lastError", message).apply()
    }
}

class WebhookJob : JobService() {
    private var cancelled = AtomicBoolean(false)
    override fun onStartJob(params: JobParameters): Boolean {
        val stop = AtomicBoolean(false)
        cancelled = stop
        delivery.execute {
            var retry = false
            try {
                // ponytail: 25 events per job and a 500-event queue; use a broker for sustained high traffic.
                var more = true
                var sent = 0
                while (!stop.get() && more && sent < 25) { more = Webhooks.deliverOne(this); sent++ }
                retry = more && !stop.get()
            } catch (e: Exception) { Webhooks.failed(this, e); retry = true }
            finally { if (!stop.get()) jobFinished(params, retry) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { cancelled.set(true); return true }
    companion object { private val delivery = Executors.newSingleThreadExecutor() }
}

class IncomingSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return
        val pending = goAsync()
        Webhooks.worker.execute {
            try {
                val sender = messages.first().originatingAddress
                Webhooks.enqueue(context, "sms.received", JSONObject().put("from", sender ?: JSONObject.NULL)
                    .put("numberE164", PhoneFeatures.e164(sender, PhoneFeatures.region(context)) ?: JSONObject.NULL)
                    .put("text", messages.joinToString("") { it.messageBody.orEmpty() })
                    .put("receivedAt", messages.first().timestampMillis).put("subscriptionId", intent.getIntExtra("subscription", -1)))
            } catch (e: Exception) { Webhooks.failed(context, e) } finally { pending.finish() }
        }
    }
}

class NotificationBridge : NotificationListenerService() {
    override fun onListenerConnected() { connected = this }
    override fun onListenerDisconnected() { connected = null }
    override fun onDestroy() { if (connected === this) connected = null; super.onDestroy() }
    override fun onNotificationPosted(notification: StatusBarNotification) { emit("notification.posted", notification) }
    override fun onNotificationRemoved(notification: StatusBarNotification) { emit("notification.removed", notification) }
    private fun emit(type: String, notification: StatusBarNotification) {
        if (notification.packageName == packageName) return
        val data = runCatching { serialize(notification) }.getOrNull() ?: return
        Webhooks.worker.execute { runCatching { Webhooks.enqueue(this, type, data) }.onFailure { Webhooks.failed(this, it as? Exception ?: Exception()) } }
    }
    companion object {
        @Volatile var connected: NotificationBridge? = null
        fun allowed(context: Context) = context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(ComponentName(context, NotificationBridge::class.java))
        fun serialize(item: StatusBarNotification): JSONObject {
            val extras = item.notification.extras
            return JSONObject().put("key", item.key).put("package", item.packageName).put("id", item.id).put("postedAt", item.postTime)
                .put("ongoing", item.isOngoing).put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(1000) ?: JSONObject.NULL)
                .put("text", (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.take(4000) ?: JSONObject.NULL)
        }
        fun list(context: Context, packageName: String?, limit: Int): JSONObject {
            if (!allowed(context)) throw SecurityException("Enable notification access in Android settings")
            val service = connected ?: throw FeatureUnavailable("Notification listener is connecting; reopen Phone API or enable notification access")
            val items = service.activeNotifications.filter { it.packageName != context.packageName && (packageName == null || it.packageName == packageName) }
                .sortedByDescending { it.postTime }.take(limit)
            return JSONObject().put("notifications", JSONArray(items.map { serialize(it) }))
        }
    }
}
