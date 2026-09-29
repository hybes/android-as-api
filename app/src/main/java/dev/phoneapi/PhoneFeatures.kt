package dev.phoneapi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FeatureUnavailable(message: String) : IllegalStateException(message)

object PhoneFeatures {
    fun granted(context: Context, permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun requireAccess(context: Context, permission: String) {
        if (!granted(context, permission)) throw SecurityException("Allow ${permission.substringAfterLast('.')} in Phone API")
    }
    fun region(context: Context): String {
        val phone = context.getSystemService(TelephonyManager::class.java)
        return sequenceOf(runCatching { phone.networkCountryIso }.getOrNull(), runCatching { phone.simCountryIso }.getOrNull(), Locale.getDefault().country, "GB")
            .first { it?.matches(Regex("[A-Za-z]{2}")) == true }!!.uppercase(Locale.ROOT)
    }
    fun e164(number: String?, region: String): String? {
        if (number == null || number.any { it.isLetter() }) return null
        val normalized = PhoneNumberUtils.normalizeNumber(number)
        if (normalized.matches(Regex("\\+[1-9][0-9]{6,14}"))) return normalized
        return PhoneNumberUtils.formatNumberToE164(normalized, region)
    }
    fun requireNumber(number: String) { require(number.matches(Regex("\\+[1-9][0-9]{6,14}"))) { "number must be E.164, for example +447700900000; URL-encode + as %2B" } }

    fun capabilities(context: Context) = JSONObject().apply {
        put("readSms", granted(context, Manifest.permission.READ_SMS)); put("sendSms", granted(context, Manifest.permission.SEND_SMS))
        put("receiveSms", granted(context, Manifest.permission.RECEIVE_SMS)); put("readSims", granted(context, Manifest.permission.READ_PHONE_STATE))
        put("contacts", granted(context, Manifest.permission.READ_CONTACTS)); put("calendar", granted(context, Manifest.permission.READ_CALENDAR))
        put("location", granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)); put("preciseLocation", granted(context, Manifest.permission.ACCESS_FINE_LOCATION))
        put("backgroundLocation", granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)); put("notificationAccess", NotificationBridge.allowed(context))
        put("notificationListenerConnected", NotificationBridge.connected != null)
        put("vibrator", context.getSystemService(VibratorManager::class.java).defaultVibrator.hasVibrator())
        put("numberRegion", region(context)); put("appVisible", MainActivity.visible)
    }

    fun battery(context: Context): JSONObject {
        val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val power = context.getSystemService(PowerManager::class.java)
        return JSONObject().apply {
            put("percent", if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL)
            put("pluggedIn", (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
            put("plugged", intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0))
            put("status", intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN))
            put("health", intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN))
            put("temperatureC", intent?.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10.0) ?: JSONObject.NULL)
            put("voltageMv", intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1))
            put("powerSave", power.isPowerSaveMode); put("deviceIdle", power.isDeviceIdleMode); put("interactive", power.isInteractive)
        }
    }
    fun network(context: Context): JSONObject {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork
        val caps = network?.let { manager.getNetworkCapabilities(it) }
        val transports = JSONArray()
        mapOf(NetworkCapabilities.TRANSPORT_WIFI to "wifi", NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_VPN to "vpn", NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "bluetooth").forEach { (type, name) -> if (caps?.hasTransport(type) == true) transports.put(name) }
        return JSONObject().put("connected", network != null).put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("metered", manager.isActiveNetworkMetered).put("transports", transports).put("addresses", PhoneInfo.addresses())
    }
    fun contacts(context: Context, limit: Int, query: String?, number: String?, region: String): JSONObject {
        requireAccess(context, Manifest.permission.READ_CONTACTS)
        val rows = JSONArray()
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER),
            null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")?.use { cursor ->
            while (rows.length() < limit && cursor.moveToNext()) {
                val name = cursor.getString(1) ?: ""
                val raw = cursor.getString(2)
                val normalized = e164(raw, region)
                if (query != null && !name.contains(query, true)) continue
                if (number != null && normalized != number) continue
                rows.put(JSONObject().put("id", cursor.getString(0)).put("name", name).put("number", raw ?: JSONObject.NULL)
                    .put("numberE164", normalized ?: JSONObject.NULL))
            }
        } ?: throw FeatureUnavailable("Contacts are unavailable")
        return JSONObject().put("contacts", rows).put("region", region)
    }
    fun calendars(context: Context): JSONObject {
        requireAccess(context, Manifest.permission.READ_CALENDAR)
        val rows = JSONArray()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf("_id", "calendar_displayName", "account_name", "calendar_timezone", "visible"), null, null, "_id ASC")?.use { cursor ->
            while (cursor.moveToNext()) rows.put(JSONObject().put("id", cursor.getString(0)).put("name", cursor.getString(1))
                .put("account", cursor.getString(2)).put("timeZone", cursor.getString(3)).put("visible", cursor.getInt(4) != 0))
        } ?: throw FeatureUnavailable("Calendars are unavailable")
        return JSONObject().put("calendars", rows)
    }
    fun events(context: Context, from: Long, to: Long, calendarId: Long?, limit: Int): JSONObject {
        requireAccess(context, Manifest.permission.READ_CALENDAR)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(from.toString()).appendPath(to.toString()).build()
        val rows = JSONArray()
        context.contentResolver.query(uri, arrayOf("event_id", "calendar_id", "title", "eventLocation", "begin", "end", "allDay"),
            if (calendarId != null) "calendar_id = ?" else null, calendarId?.let { arrayOf(it.toString()) }, "begin ASC, event_id ASC")?.use { cursor ->
            while (rows.length() < limit && cursor.moveToNext()) rows.put(JSONObject().put("id", cursor.getString(0)).put("calendarId", cursor.getString(1))
                .put("title", cursor.getString(2) ?: JSONObject.NULL).put("location", cursor.getString(3) ?: JSONObject.NULL)
                .put("begin", cursor.getLong(4)).put("end", cursor.getLong(5)).put("allDay", cursor.getInt(6) != 0))
        } ?: throw FeatureUnavailable("Calendar events are unavailable")
        return JSONObject().put("events", rows).put("from", from).put("to", to)
    }

    private val locating = AtomicBoolean(false)
    fun location(context: Context, fresh: Boolean, timeoutSeconds: Int): JSONObject {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) throw SecurityException("Allow location access in Phone API")
        if (!MainActivity.visible) requireAccess(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val manager = context.getSystemService(LocationManager::class.java)
        if (!manager.isLocationEnabled) throw FeatureUnavailable("Turn on location in Android settings")
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { manager.hasProvider(it) && manager.isProviderEnabled(it) }
        if (providers.isEmpty()) throw FeatureUnavailable("No location provider is enabled")
        var fix: Location? = null
        if (fresh) {
            if (!locating.compareAndSet(false, true)) throw FeatureUnavailable("A location request is already in progress")
            val cancel = CancellationSignal()
            val latch = CountDownLatch(1)
            try {
                manager.getCurrentLocation(providers.first(), android.location.LocationRequest.Builder(0)
                    .setDurationMillis(timeoutSeconds * 1000L).build(), cancel, context.mainExecutor) {
                    fix = it; latch.countDown()
                }
                latch.await(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            } finally { cancel.cancel(); locating.set(false) }
        } else fix = providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.elapsedRealtimeNanos }
        return JSONObject().put("mode", if (fresh) "fresh" else "cached").put("available", fix != null).put("location", fix?.let {
            JSONObject().put("latitude", it.latitude).put("longitude", it.longitude).put("accuracyMetres", it.accuracy)
                .put("timestamp", it.time).put("ageSeconds", ((android.os.SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000_000L).coerceAtLeast(0))
                .put("provider", it.provider).put("mock", it.isMock)
        } ?: JSONObject.NULL)
    }

    private val main = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private val stopRing = Runnable { ringtone?.stop(); ringtone = null }
    fun ring(context: Context, seconds: Int) {
        val latch = CountDownLatch(1)
        var failure: Exception? = null
        main.post {
            try {
                main.removeCallbacks(stopRing); stopRing.run()
                if (seconds > 0) {
                    ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                        ?: throw FeatureUnavailable("No ringtone is configured")
                    ringtone!!.play(); main.postDelayed(stopRing, seconds * 1000L)
                }
            } catch (e: Exception) { failure = e } finally { latch.countDown() }
        }
        if (!latch.await(2, TimeUnit.SECONDS)) throw FeatureUnavailable("The ringtone control is busy")
        failure?.let { throw it }
    }
    fun vibrate(context: Context, milliseconds: Long) {
        val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
        if (!vibrator.hasVibrator()) throw FeatureUnavailable("This device has no vibrator")
        vibrator.vibrate(VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
