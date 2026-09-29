package dev.phoneapi

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.security.SecureRandom

class MainActivity : Activity() {
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var endpoint: TextView
    private lateinit var networkDetail: TextView
    private lateinit var addresses: TextView
    private lateinit var sims: TextView
    private lateinit var permissions: TextView
    private lateinit var accessButton: Button
    private lateinit var error: TextView
    private lateinit var enabled: Switch
    private lateinit var keyInput: EditText
    private lateinit var scroll: ScrollView
    private lateinit var featureAccess: TextView
    private lateinit var webhookStatus: TextView
    private var updating = false
    private var apiUrl: String? = null
    private var expandedAddresses = false
    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { update() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String = "", size: Float = 16f, secondary: Boolean = false) = TextView(this).apply {
        text = value; textSize = size
        setTextColor(getColor(if (secondary) R.color.text_secondary else R.color.text_primary))
        setPadding(0, dp(4), 0, dp(8))
    }
    private fun button(title: String, primary: Boolean = false, action: () -> Unit) = Button(this, null, android.R.attr.buttonStyle).apply {
        text = title; isAllCaps = false; minHeight = dp(48)
        if (primary) {
            backgroundTintList = ColorStateList.valueOf(getColor(if (resources.configuration.uiMode and 0x30 == 0x20)
                android.R.color.system_accent1_200 else android.R.color.system_accent1_600))
            setTextColor(getColor(R.color.on_accent))
        } else {
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.button_background))
            setTextColor(getColor(R.color.text_primary))
        }
        setOnClickListener { action() }
    }
    private fun section(title: String) {
        if (body.childCount > 0) body.addView(View(this).apply { setBackgroundColor(getColor(R.color.divider)) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(20), 0, dp(20)) })
        body.addView(label(title, 18f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
    }
    private fun actions(vararg buttons: Button) {
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(getColor(R.color.background)) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        header.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Phone API", 27f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }, LinearLayout.LayoutParams(0, -2, 1f))
            enabled = Switch(this@MainActivity).apply {
                text = "Run API"; textSize = 14f; minHeight = dp(48)
                setTextColor(getColor(R.color.text_primary))
                setOnCheckedChangeListener { _, checked ->
                    if (!updating) {
                        val intent = Intent(this@MainActivity, ApiService::class.java).setAction(if (checked) ApiService.START else ApiService.STOP)
                        runCatching { if (checked) startForegroundService(intent) else startService(intent) }
                            .onFailure { toast("Android could not start the service") }
                    }
                }
            }
            addView(enabled)
        })
        status = label("Stopped", 14f, true).also { header.addView(it) }
        val headerFrame = FrameLayout(this).apply { addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL)) }
        root.addView(headerFrame)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(28)) }
        val bodyFrame = FrameLayout(this).apply { addView(body, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL)) }
        scroll = ScrollView(this).apply { isFillViewport = true; addView(bodyFrame) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        section("Connection")
        endpoint = label("Connect Tailscale to get your API URL", 20f).apply { setTextIsSelectable(true); body.addView(this) }
        networkDetail = label("", 14f, true).also { body.addView(it) }
        actions(button("Copy URL") { apiUrl?.let { copy("Phone API URL", it, false); toast("URL copied") } ?: toast("Connect Tailscale first") },
            button("Open stats") { apiUrl?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } ?: toast("Connect Tailscale first") })
        actions(button("Open Tailscale") {
            packageManager.getLaunchIntentForPackage("com.tailscale.ipn")?.let { startActivity(it) } ?: toast("Install Tailscale to connect")
        }, button("Other IP addresses") {
            expandedAddresses = !expandedAddresses
            addresses.visibility = if (expandedAddresses) View.VISIBLE else View.GONE
        })
        addresses = label("", 13f, true).apply { setTextIsSelectable(true); visibility = View.GONE; body.addView(this) }
        error = label("", 14f, true).apply { visibility = View.GONE; body.addView(this) }

        section("SIMs")
        sims = label("").also { body.addView(it) }
        body.addView(label("Use the SIM ID as subscriptionId when sending an SMS.", 14f, true))

        section("Access")
        permissions = label("", 14f, true).also { body.addView(it) }
        accessButton = button("Allow SMS and SIM access", true) {
            val missing = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_PHONE_STATE, Manifest.permission.POST_NOTIFICATIONS).filter { !granted(it) }
            if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
            else startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.also { body.addView(it) }

        section("Additional access")
        featureAccess = label("", 14f, true).also { body.addView(it) }
        actions(button("Contacts") { allow(Manifest.permission.READ_CONTACTS) }, button("Calendar") { allow(Manifest.permission.READ_CALENDAR) })
        actions(button("Location") { allow(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION) },
            button("Background location") {
                if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION)) toast("Allow location first")
                else startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        body.addView(button("Notification access") {
            startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    android.content.ComponentName(this, NotificationBridge::class.java).flattenToString()))
        })
        body.addView(label("For location through the API while the app is closed, choose Allow all the time in Android settings. Location is requested only when needed.", 13f, true))

        section("Webhooks")
        body.addView(label("Off until enabled. Send SMS and selected app notifications to your own endpoint.", 14f, true))
        val config = Webhooks.status(this)
        fun input(hintText: String, value: String, secret: Boolean = false) = EditText(this).apply {
            setSingleLine(true); hint = hintText; contentDescription = hintText
            inputType = android.text.InputType.TYPE_CLASS_TEXT or if (secret) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(value); setTextColor(getColor(R.color.text_primary)); minHeight = dp(52)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            filters = arrayOf(android.text.InputFilter.LengthFilter(if (secret) 512 else 2048)); body.addView(this)
        }
        val webhookUrl = input("Webhook URL", config.getString("url"))
        val webhookToken = input("Webhook Bearer token (optional)", "", true)
        body.addView(label("Leave the token empty to keep it for the same URL. Changing the URL clears the old token and queued events.", 13f, true))
        val webhookPackages = input("Notification package names, comma-separated", config.getJSONArray("packages").let { array -> (0 until array.length()).joinToString(", ") { array.getString(it) } })
        body.addView(label("Only these apps are forwarded. Get package names from /notifications. No notification apps are selected by default.", 13f, true))
        fun toggle(title: String, checked: Boolean) = CheckBox(this).apply { text = title; isChecked = checked; minHeight = dp(48); setTextColor(getColor(R.color.text_primary)); body.addView(this) }
        val webhookEnabled = toggle("Enable webhook delivery", config.getBoolean("enabled"))
        val events = config.getJSONArray("events").let { array -> (0 until array.length()).map { array.getString(it) } }
        val webhookSms = toggle("Incoming SMS", "sms.received" in events)
        val webhookNotifications = toggle("Notification posts and removals", "notification.posted" in events || "notification.removed" in events)
        webhookStatus = label("", 13f, true).also { body.addView(it) }
        actions(button("Save webhooks", true) {
            try {
                val selected = org.json.JSONArray()
                if (webhookSms.isChecked) selected.put("sms.received")
                if (webhookNotifications.isChecked) { selected.put("notification.posted"); selected.put("notification.removed") }
                val packages = org.json.JSONArray(webhookPackages.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() })
                val settings = org.json.JSONObject().put("url", webhookUrl.text.toString().trim()).put("enabled", webhookEnabled.isChecked).put("events", selected).put("packages", packages)
                if (webhookToken.text.isNotEmpty()) settings.put("token", webhookToken.text.toString())
                Webhooks.configure(this, settings); webhookToken.text.clear(); update(); toast("Webhooks saved")
                getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(webhookUrl.windowToken, 0)
            } catch (e: IllegalArgumentException) { toast(e.message ?: "Check webhook settings") }
        }, button("Send test") {
            val id = Webhooks.enqueue(this, "test", org.json.JSONObject().put("message", "Phone API webhook test"), true)
            toast(if (id == null) "Enable the API and save webhook settings first" else "Test queued; check /webhooks for delivery status")
            update()
        })
        body.addView(button("Clear webhook token") { Webhooks.configure(this, org.json.JSONObject().put("token", "")); update(); toast("Webhook token cleared") })

        section("API key")
        body.addView(label("Saved on this phone. Changing it takes effect without stopping the API.", 14f, true))
        keyInput = EditText(this).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(token(this@MainActivity)); setTextColor(getColor(R.color.text_primary))
            contentDescription = "API key"; filters = arrayOf(android.text.InputFilter.LengthFilter(128))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            minHeight = dp(52); body.addView(this)
        }
        body.addView(CheckBox(this).apply {
            text = "Show key"; minHeight = dp(48); setTextColor(getColor(R.color.text_secondary))
            setOnCheckedChangeListener { _, checked ->
                keyInput.transformationMethod = if (checked) null else android.text.method.PasswordTransformationMethod.getInstance()
                keyInput.setSelection(keyInput.text.length)
            }
        })
        actions(button("Save key", true) {
            val key = keyInput.text.toString()
            if (!key.matches(Regex("[A-Za-z0-9_-]{1,128}"))) toast("Use 1 to 128 letters, digits, underscores or hyphens")
            else if (getSharedPreferences("api", MODE_PRIVATE).edit().putString("token", key).commit()) {
                keyInput.clearFocus()
                getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(keyInput.windowToken, 0)
                toast("Key saved")
            } else toast("Could not save the key")
        }, button("Copy key") { copy("Phone API key", token(this), true); toast("Key copied") })
        body.addView(button("Generate random key") {
            android.app.AlertDialog.Builder(this).setMessage("Replace the key? Existing clients will need the new one.")
                .setNegativeButton("Cancel", null).setPositiveButton("Replace") { _, _ -> keyInput.setText(token(this, true)); toast("Key replaced") }.show()
        })
        body.addView(label("Browser login: api / your key. API clients use a Bearer key. See /docs for examples.", 13f, true))

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val width = minOf(root.width - root.paddingLeft - root.paddingRight, dp(600))
            if (width > 0) listOf(header, body).forEach { child ->
                if (child.layoutParams.width != width) child.layoutParams = FrameLayout.LayoutParams(width, -2, Gravity.CENTER_HORIZONTAL)
            }
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            if (keyboard.bottom > 0 && keyInput.hasFocus()) scroll.post { scroll.scrollTo(0, scroll.getChildAt(0).height) }
            else if (keyboard.bottom > 0) scroll.post { root.findFocus()?.let { focused -> focused.requestRectangleOnScreen(android.graphics.Rect(0, 0, focused.width, focused.height + dp(70)), false) } }
            WindowInsets.CONSUMED
        }
        setContentView(root)
        root.requestApplyInsets()
        update()
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun allow(vararg permissions: String) {
        val missing = permissions.filter { !granted(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 2)
        else toast("Access is already allowed")
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun copy(label: String, text: String, sensitive: Boolean) {
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", sensitive) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private fun update() {
        val prefs = getSharedPreferences("api", MODE_PRIVATE)
        updating = true
        enabled.isChecked = prefs.getBoolean("enabled", false)
        updating = false
        status.text = if (!enabled.isChecked) "Stopped" else if (!ApiService.running) "Starting the API…" else if (ApiService.listeners.size > 1) "Running in the background" else "Waiting for Tailscale · running locally"
        val ips = runCatching { PhoneInfo.addresses() }.getOrNull()
        val entries = if (ips == null) emptyList() else (0 until ips.length()).map { ips.getJSONObject(it) }
        val tailnet = entries.firstOrNull { it.getString("kind") == "tailscale" && !it.getString("address").contains(':') }
        apiUrl = tailnet?.let { "http://${it.getString("address")}:8765/" }
        endpoint.text = apiUrl ?: "Connect Tailscale to get your API URL"
        networkDetail.text = if (tailnet == null) "Tailscale is not connected" else "Private access through your tailnet"
        addresses.text = entries.joinToString("\n") { "${it.getString("interface")}  ${it.getString("address")}" }
        sims.text = if (!granted(Manifest.permission.READ_PHONE_STATE)) "Allow SIM access to show your SIMs" else runCatching {
            val subscriptions = PhoneInfo.subscriptions(this)
            if (subscriptions.length() == 0) "No active SIMs" else (0 until subscriptions.length()).joinToString("\n\n") {
                val sim = subscriptions.getJSONObject(it)
                "${sim.getString("name")}\nSIM ID ${sim.getInt("subscriptionId")} · ${if (sim.getBoolean("embedded")) "eSIM" else "Physical SIM"}" +
                    if (sim.getBoolean("defaultSms")) " · Default SMS" else ""
            }
        }.getOrDefault("SIM details are unavailable")
        val read = granted(Manifest.permission.READ_SMS); val send = granted(Manifest.permission.SEND_SMS)
        val simAccess = granted(Manifest.permission.READ_PHONE_STATE)
        permissions.text = "SMS reading: ${if (read) "allowed" else "not allowed"}\nSMS sending: ${if (send) "allowed" else "not allowed"}\nSIM details: ${if (simAccess) "allowed" else "not allowed"}"
        accessButton.text = if (read && send && simAccess && granted(Manifest.permission.RECEIVE_SMS) && granted(Manifest.permission.POST_NOTIFICATIONS)) "Manage permissions" else "Allow SMS and SIM access"
        featureAccess.text = listOf("Incoming SMS" to granted(Manifest.permission.RECEIVE_SMS), "Contacts" to granted(Manifest.permission.READ_CONTACTS),
            "Calendar" to granted(Manifest.permission.READ_CALENDAR), "Location" to granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            "Background location" to granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION), "Notifications" to NotificationBridge.allowed(this))
            .joinToString("\n") { "${it.first}: ${if (it.second) "allowed" else "not allowed"}" }
        val hook = Webhooks.status(this)
        webhookStatus.text = "${if (hook.getBoolean("enabled")) "Enabled" else "Off"} · ${hook.getInt("pending")} queued · ${hook.getLong("delivered")} delivered" +
            if (hook.getString("lastError").isNotEmpty()) "\n${hook.getString("lastError")}" else ""
        val problem = prefs.getString("error", null)
        error.text = problem
        error.visibility = if (problem == null) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        visible = true
        registerReceiver(changes, IntentFilter(ApiService.STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        if (getSharedPreferences("api", MODE_PRIVATE).getBoolean("enabled", false) && !ApiService.active) startForegroundService(Intent(this, ApiService::class.java))
        update()
    }
    override fun onPause() { visible = false; unregisterReceiver(changes); super.onPause() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); update()
    }

    companion object {
        @Volatile var visible = false
        @Synchronized fun token(context: Context, replace: Boolean = false): String {
            val prefs = context.getSharedPreferences("api", MODE_PRIVATE)
            return (if (replace) null else prefs.getString("token", null)) ?: android.util.Base64.encodeToString(
                ByteArray(32).also { SecureRandom().nextBytes(it) },
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            ).also { check(prefs.edit().putString("token", it).commit()) }
        }
    }
}
