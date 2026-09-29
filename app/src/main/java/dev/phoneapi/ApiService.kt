package dev.phoneapi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class ApiService : Service() {
    private val servers = mutableMapOf<String, ApiServer>()
    private val handler = Handler(Looper.getMainLooper())
    private var watchingNetwork = false
    private var retryDelay = 1000L
    private val retry = Runnable { reconcile() }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { reconcile() }
        override fun onLost(network: Network) { reconcile() }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { reconcile() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("api", MODE_PRIVATE)
        if (intent?.action == STOP) {
            prefs.edit().putBoolean("enabled", false).apply()
            Webhooks.pause(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == START) prefs.edit().putBoolean("enabled", true).apply()
        if (!prefs.getBoolean("enabled", false)) { stopSelf(); return START_NOT_STICKY }
        if (active) return START_STICKY
        startedAt = android.os.SystemClock.elapsedRealtime()
        requests.set(0)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("api", "Phone API", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notification("Connecting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        active = true
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback, handler)
        watchingNetwork = true
        reconcile()
        Webhooks.schedule(this)
        return START_STICKY
    }

    private fun notification(detail: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ApiService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "api").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Phone API").setContentText(detail).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop API", stop).build()).build()
    }

    private fun reconcile() {
        if (!active) return
        handler.removeCallbacks(retry)
        var failed = false
        try {
            val ips = PhoneInfo.addresses()
            val desired = mutableSetOf("127.0.0.1")
            for (i in 0 until ips.length()) {
                val ip = ips.getJSONObject(i)
                if (ip.getString("kind") == "tailscale") desired.add(ip.getString("address"))
            }
            (servers.keys - desired).forEach { servers.remove(it)?.stop() }
            (desired - servers.keys).forEach { address ->
                val server = ApiServer(this, address)
                try { server.start(5000, false); servers[address] = server }
                catch (e: Exception) {
                    server.stop()
                    failed = true
                    android.util.Log.w("PhoneAPI", "Network listener not ready", e)
                }
            }
        } catch (e: Exception) {
            failed = true
            android.util.Log.w("PhoneAPI", "Network addresses unavailable", e)
        }
        listeners = servers.keys.toSet()
        running = listeners.isNotEmpty()
        val prefs = getSharedPreferences("api", MODE_PRIVATE)
        if (failed) {
            prefs.edit().putString("error", "Waiting for the network listener. Retrying automatically.").apply()
            // Retry only after a failure; idle operation has no timer, polling or wake lock.
            handler.postDelayed(retry, retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(60_000L)
        } else {
            prefs.edit().remove("error").apply()
            retryDelay = 1000L
        }
        val detail = if (failed) "Waiting for network · retrying" else if (listeners.size > 1) "Running through Tailscale · port 8765" else "Running locally · waiting for Tailscale"
        getSystemService(NotificationManager::class.java).notify(1, notification(detail))
        sendBroadcast(Intent(STATE_CHANGED).setPackage(packageName))
    }

    override fun onDestroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        if (watchingNetwork) getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        servers.values.forEach { it.stop() }
        servers.clear()
        listeners = emptySet()
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        sendBroadcast(Intent(STATE_CHANGED).setPackage(packageName))
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val START = "dev.phoneapi.START"
        const val STOP = "dev.phoneapi.STOP"
        const val STATE_CHANGED = "dev.phoneapi.STATE_CHANGED"
        @Volatile var active = false
        @Volatile var running = false
        @Volatile var listeners: Set<String> = emptySet()
        @Volatile var startedAt = 0L
        val requests = java.util.concurrent.atomic.AtomicLong()
    }
}

class ResumeApiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (!context.getSharedPreferences("api", Context.MODE_PRIVATE).getBoolean("enabled", false)) return
        try { context.startForegroundService(Intent(context, ApiService::class.java)) }
        catch (e: IllegalStateException) {
            context.getSharedPreferences("api", Context.MODE_PRIVATE).edit()
                .putString("error", "Android could not resume the API. Open Phone API to resume it.").apply()
            android.util.Log.w("PhoneAPI", "Background restart not allowed", e)
        }
    }
}
