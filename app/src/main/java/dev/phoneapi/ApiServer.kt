package dev.phoneapi

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class ApiServer(private val context: Context, address: String) : NanoHTTPD(address, 8765) {

    init {
        // ponytail: cap at eight concurrent clients; use a pooled server if this becomes a shared service.
        setAsyncRunner(object : DefaultAsyncRunner() {
            @Synchronized override fun exec(handler: ClientHandler) {
                if (running.size >= 8) handler.close() else super.exec(handler)
            }
        })
    }

    override fun serve(original: IHTTPSession): Response {
        var consumed = 0L
        val input = object : java.io.FilterInputStream(original.inputStream) {
            override fun read(): Int = super.read().also { if (it >= 0) consumed++ }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, length).also { if (it > 0) consumed += it }
        }
        val session = object : IHTTPSession by original { override fun getInputStream(): java.io.InputStream = input }
        ApiService.requests.incrementAndGet()
        try {
            if (session.uri in setOf("/docs", "/openapi.json")) {
                if (session.method != Method.GET) return method("GET")
                val docs = session.uri == "/docs"
                val content = context.assets.open(if (docs) "docs.html" else "openapi.json").bufferedReader().use { it.readText() }
                return newFixedLengthResponse(Response.Status.OK, if (docs) "text/html; charset=utf-8" else "application/json; charset=utf-8", content).apply {
                    addHeader("Cache-Control", "no-store")
                    addHeader("X-Content-Type-Options", "nosniff")
                    addHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
                    closeConnection(true)
                }
            }
            val supplied = session.headers["authorization"] ?: ""
            val token = MainActivity.token(context)
            val bearer = MessageDigest.isEqual(supplied.toByteArray(), "Bearer $token".toByteArray())
            val basic = if (supplied.startsWith("Basic ")) runCatching {
                val credentials = String(android.util.Base64.decode(supplied.removePrefix("Basic "), android.util.Base64.DEFAULT), Charsets.UTF_8)
                MessageDigest.isEqual(credentials.toByteArray(), "api:$token".toByteArray())
            }.getOrDefault(false) else false
            if (!bearer && !basic) {
                return error(Response.Status.UNAUTHORIZED, "An API key is required").apply { addHeader("WWW-Authenticate", "Basic realm=\"Phone API\", charset=\"UTF-8\"") }
            }
            return when (session.uri) {
                "/" -> {
                    if (session.method != Method.GET) return method("GET")
                    val stats = statistics()
                    val data = stats.toString(2).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", """
                        <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                        <title>Phone API</title><style>:root{color-scheme:light dark}body{font:16px system-ui,sans-serif;margin:32px auto;padding:0 20px;max-width:760px}h1{font-size:28px}pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:14px;line-height:1.6}a{color:inherit}</style>
                        <h1>Phone API</h1><p>${if (stats.getBoolean("ready")) "Ready" else "Setup needed"} · ${stats.getLong("uptimeSeconds")} seconds running</p>
                        <p><a href="/docs">API documentation</a> · <a href="/health">Health</a> · <a href="/health/live">Liveness</a> · <a href="/health/ready">Readiness</a> · <a href="/sms/subscriptions">SIMs</a></p><pre>$data</pre></html>
                    """.trimIndent()).apply {
                        addHeader("Cache-Control", "no-store")
                        addHeader("X-Content-Type-Options", "nosniff")
                        addHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
                        closeConnection(true)
                    }
                }
                "/health", "/device/status", "/stats", "/health/ready" -> {
                    if (session.method != Method.GET) return method("GET")
                    val stats = statistics()
                    val code = if (session.uri == "/health/ready" && !stats.getBoolean("ready")) Response.Status.SERVICE_UNAVAILABLE else Response.Status.OK
                    json(code, stats)
                }
                "/health/live" -> {
                    if (session.method != Method.GET) return method("GET")
                    json(Response.Status.OK, JSONObject().put("status", "ok").put("uptimeSeconds", uptime()))
                }
                "/sms/messages" -> inbox(session)
                "/capabilities", "/device/battery", "/device/network", "/contacts", "/calendar/calendars", "/calendar/events", "/location", "/notifications", "/webhooks", "/webhooks/test", "/device/ring", "/device/vibrate" -> features(session)
                "/sms/subscriptions" -> {
                    if (session.method != Method.GET) return method("GET")
                    json(Response.Status.OK, JSONObject().put("subscriptions", PhoneInfo.subscriptions(context)))
                }
                "/sms/send" -> send(session)
                else -> {
                    if (session.uri.startsWith("/sms/requests/")) {
                        if (session.method != Method.GET) return method("GET")
                        val id = session.uri.removePrefix("/sms/requests/")
                        val request = SmsStore(context).use { it.get(id) }
                        if (request == null) error(Response.Status.NOT_FOUND, "SMS request not found")
                        else json(Response.Status.OK, request)
                    } else error(Response.Status.NOT_FOUND, "Endpoint not found")
                }
            }
        } catch (e: SecurityException) {
            return error(Response.Status.FORBIDDEN, if (e.message?.startsWith("Allow ") == true || e.message?.startsWith("Enable ") == true) e.message!! else "Enable the required permission in Phone API")
        } catch (e: FeatureUnavailable) {
            return error(Response.Status.SERVICE_UNAVAILABLE, e.message ?: "Feature is unavailable")
        } catch (e: IllegalArgumentException) {
            return error(Response.Status.BAD_REQUEST, e.message ?: "Invalid request")
        } catch (_: org.json.JSONException) {
            return error(Response.Status.BAD_REQUEST, "Invalid JSON request")
        } catch (_: Exception) {
            return error(Response.Status.INTERNAL_ERROR, "The request could not be completed")
        } finally {
            // Read rejected bounded bodies before closing TCP, so clients receive the error reliably.
            val remaining = ((session.headers["content-length"]?.toLongOrNull() ?: 0) - consumed).coerceAtLeast(0)
            if (remaining > 0) runCatching { input.readNBytes(minOf(remaining, 65536).toInt()) }
        }
    }

    private fun uptime() = ((android.os.SystemClock.elapsedRealtime() - ApiService.startedAt) / 1000).coerceAtLeast(0)

    private fun statistics(): JSONObject {
        val addresses = PhoneInfo.addresses()
        val sims = if (granted(Manifest.permission.READ_PHONE_STATE)) PhoneInfo.subscriptions(context) else JSONArray()
        val setup = JSONArray()
        if (!granted(Manifest.permission.READ_SMS)) setup.put("Enable SMS reading")
        if (!granted(Manifest.permission.SEND_SMS)) setup.put("Enable SMS sending")
        if (!granted(Manifest.permission.READ_PHONE_STATE)) setup.put("Enable SIM access")
        if (granted(Manifest.permission.READ_PHONE_STATE) && sims.length() == 0) setup.put("Activate a SIM")
        if ((0 until addresses.length()).none { addresses.getJSONObject(it).getString("kind") == "tailscale" }) setup.put("Connect Tailscale")
        if (ApiService.listeners.size < 2) setup.put("Waiting for the Tailscale listener")
        val counts = JSONObject()
        SmsStore(context).use { store ->
            store.readableDatabase.rawQuery("SELECT state, COUNT(*) FROM requests GROUP BY state", null).use { cursor ->
                while (cursor.moveToNext()) counts.put(cursor.getString(0), cursor.getInt(1))
            }
        }
        return JSONObject().apply {
            put("running", true); put("ready", setup.length() == 0); put("setupNeeded", setup)
            put("model", Build.MODEL); put("android", Build.VERSION.RELEASE); put("apiLevel", Build.VERSION.SDK_INT)
            put("version", "0.2.0"); put("uptimeSeconds", uptime()); put("httpRequests", ApiService.requests.get())
            put("backgroundEnabled", context.getSharedPreferences("api", Context.MODE_PRIVATE).getBoolean("enabled", false))
            put("listeners", JSONArray(ApiService.listeners.toList()))
            put("readSms", granted(Manifest.permission.READ_SMS)); put("sendSms", granted(Manifest.permission.SEND_SMS))
            put("readSims", granted(Manifest.permission.READ_PHONE_STATE)); put("addresses", addresses); put("subscriptions", sims)
            put("defaultSmsSubscriptionId", SubscriptionManager.getDefaultSmsSubscriptionId()); put("smsRequests", counts)
            put("battery", PhoneFeatures.battery(context)); put("network", PhoneFeatures.network(context))
            put("capabilities", PhoneFeatures.capabilities(context))
        }
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun requirePermission(permission: String) { if (!granted(permission)) throw SecurityException() }

    private fun inbox(session: IHTTPSession): Response {
        if (session.method != Method.GET) return method("GET")
        requirePermission(Manifest.permission.READ_SMS)
        val limit = session.parameters["limit"]?.singleOrNull()?.toIntOrNull() ?: if (session.parameters.containsKey("limit")) 0 else 20
        val since = session.parameters["after"]?.singleOrNull()?.toLongOrNull() ?: if (session.parameters.containsKey("after")) -1 else 0
        require(limit in 1..100) { "limit must be between 1 and 100" }
        require(since >= 0) { "after must be a non-negative timestamp in milliseconds" }
        val number = parameter(session, "number")?.also { PhoneFeatures.requireNumber(it) }
        val region = numberRegion(session)
        val box = parameter(session, "box") ?: if (number == null) "inbox" else "all"
        require(box in setOf("inbox", "sent", "all")) { "box must be inbox, sent or all" }
        val before = parameter(session, "before")?.toLongOrNull() ?: if (session.parameters.containsKey("before")) -1 else Long.MAX_VALUE
        require(before >= 0 && (!session.parameters.containsKey("before") || before > since)) { "before must be a timestamp greater than after" }
        val messages = JSONArray()
        context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("_id", "address", "body", "date", "sub_id", "type"),
            "date > ? AND date < ? AND type IN (${if (box == "all") "1,2" else if (box == "sent") "2" else "1"})", arrayOf(since.toString(), before.toString()), "date DESC, _id DESC")?.use { cursor ->
            while (messages.length() < limit && cursor.moveToNext()) {
                val raw = cursor.getString(1)
                val normalized = PhoneFeatures.e164(raw, region)
                if (number != null && normalized != number) continue
                messages.put(JSONObject().apply {
                    put("id", cursor.getString(0)); put("from", if (cursor.getInt(5) == 1) raw ?: JSONObject.NULL else JSONObject.NULL)
                    put("address", raw ?: JSONObject.NULL); put("numberE164", normalized ?: JSONObject.NULL)
                    put("direction", if (cursor.getInt(5) == 1) "incoming" else "outgoing")
                    put("text", cursor.getString(2) ?: JSONObject.NULL); put("receivedAt", cursor.getLong(3))
                    put("subscriptionId", cursor.getInt(4))
                })
            }
        } ?: return error(Response.Status.INTERNAL_ERROR, "The SMS inbox is unavailable")
        return json(Response.Status.OK, JSONObject().put("messages", messages).put("region", region).put("box", box))
    }

    private fun parameter(session: IHTTPSession, name: String): String? {
        val values = session.parameters[name] ?: return null
        require(values.size == 1) { "Supply $name once" }
        return values.single()
    }
    private fun numberRegion(session: IHTTPSession): String = (parameter(session, "region") ?: PhoneFeatures.region(context)).uppercase(java.util.Locale.ROOT).also {
        require(it.matches(Regex("[A-Z]{2}"))) { "region must be a two-letter country code" }
    }
    private fun limit(session: IHTTPSession, fallback: Int = 20) = (parameter(session, "limit")?.let { it.toIntOrNull() ?: 0 } ?: fallback).also {
        require(it in 1..100) { "limit must be between 1 and 100" }
    }
    private fun body(session: IHTTPSession): JSONObject {
        require(!session.headers.containsKey("transfer-encoding")) { "Use Content-Length instead of chunked encoding" }
        require(session.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() == "application/json") { "Use application/json" }
        val size = session.headers["content-length"]?.toIntOrNull() ?: throw IllegalArgumentException("Content-Length is required")
        require(size in 1..8192) { "Body must be between 1 and 8192 bytes" }
        val bytes = session.inputStream.readNBytes(size)
        require(bytes.size == size) { "Incomplete request body" }
        return JSONObject(String(bytes, Charsets.UTF_8))
    }
    private fun features(session: IHTTPSession): Response {
        val path = session.uri
        if (path == "/webhooks") {
            if (session.method == Method.GET) return json(Response.Status.OK, Webhooks.status(context))
            if (session.method != Method.PUT) return method("GET, PUT")
            return json(Response.Status.OK, Webhooks.configure(context, body(session)))
        }
        if (path == "/webhooks/test") {
            if (session.method != Method.POST) return method("POST")
            val id = Webhooks.enqueue(context, "test", JSONObject().put("message", "Phone API webhook test"), true)
                ?: return error(Response.Status.CONFLICT, "Enable Run API and webhook delivery first")
            return json(Response.Status.ACCEPTED, JSONObject().put("id", id).put("queued", true))
        }
        if (path == "/device/ring" && session.method == Method.DELETE) {
            PhoneFeatures.ring(context, 0)
            return json(Response.Status.OK, JSONObject().put("stopped", true))
        }
        if (path in setOf("/device/ring", "/device/vibrate")) {
            if (session.method != Method.POST) return method(if (path == "/device/ring") "POST, DELETE" else "POST")
            val body = body(session)
            val field = if (path == "/device/ring") "durationSeconds" else "durationMs"
            require(body.keys().asSequence().all { it == field }) { "Supply only $field" }
            val value = body.opt(field) as? Int ?: throw IllegalArgumentException("$field must be an integer")
            require(value in 1..if (field == "durationSeconds") 30 else 5000) { "$field is out of range" }
            if (field == "durationSeconds") PhoneFeatures.ring(context, value) else PhoneFeatures.vibrate(context, value.toLong())
            return json(Response.Status.OK, JSONObject().put("requested", true).put(field, value))
        }
        if (session.method != Method.GET) return method("GET")
        val result = when (path) {
            "/capabilities" -> PhoneFeatures.capabilities(context)
            "/device/battery" -> PhoneFeatures.battery(context)
            "/device/network" -> PhoneFeatures.network(context)
            "/contacts" -> {
                val number = parameter(session, "number")?.also { PhoneFeatures.requireNumber(it) }
                val query = parameter(session, "q")?.also { require(it.length <= 100) { "q must be at most 100 characters" } }
                PhoneFeatures.contacts(context, limit(session), query, number, numberRegion(session))
            }
            "/calendar/calendars" -> PhoneFeatures.calendars(context)
            "/calendar/events" -> {
                val from = parameter(session, "from")?.let { it.toLongOrNull() ?: -1 } ?: System.currentTimeMillis()
                val to = parameter(session, "to")?.let { it.toLongOrNull() ?: -1 } ?: (from + 7 * 86400_000L)
                require(from >= 0 && to > from && to - from <= 31 * 86400_000L) { "Supply a from/to range of at most 31 days in Unix milliseconds" }
                val calendar = parameter(session, "calendarId")?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw IllegalArgumentException("calendarId must be a non-negative integer") }
                PhoneFeatures.events(context, from, to, calendar, limit(session, 50))
            }
            "/location" -> {
                val fresh = parameter(session, "fresh") ?: "false"
                require(fresh in setOf("true", "false")) { "fresh must be true or false" }
                val timeout = parameter(session, "timeoutSeconds")?.let { it.toIntOrNull() ?: 0 } ?: 10
                require(timeout in 1..15) { "timeoutSeconds must be between 1 and 15" }
                PhoneFeatures.location(context, fresh == "true", timeout)
            }
            "/notifications" -> NotificationBridge.list(context, parameter(session, "package"), limit(session))
            else -> throw IllegalArgumentException("Unknown feature")
        }
        return json(Response.Status.OK, result)
    }

    private fun send(session: IHTTPSession): Response {
        if (session.method != Method.POST) return method("POST")
        if (session.headers.containsKey("transfer-encoding")) return error(Response.Status.BAD_REQUEST, "Use Content-Length instead of chunked encoding")
        if (session.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
            return error(Response.Status.UNSUPPORTED_MEDIA_TYPE, "Use application/json")
        }
        val length = session.headers["content-length"]?.toIntOrNull() ?: return error(Response.Status.LENGTH_REQUIRED, "Content-Length is required")
        if (length !in 1..8192) {
            // Drain a bounded rejected body so closing TCP does not discard the 413 response.
            // ponytail: bodies above 64 KiB may get a connection reset; a streaming server is needed for uploads.
            if (length > 0) session.inputStream.readNBytes(minOf(length, 65536))
            return error(Response.Status.PAYLOAD_TOO_LARGE, "The request body must be between 1 and 8192 bytes")
        }
        val key = session.headers["idempotency-key"] ?: ""
        require(key.matches(Regex("[A-Za-z0-9_-]{16,128}"))) { "Supply an Idempotency-Key of 16 to 128 letters, digits, underscores or hyphens" }
        val bytes = session.inputStream.readNBytes(length)
        require(bytes.size == length) { "Incomplete request body" }
        val body = JSONObject(String(bytes, Charsets.UTF_8))
        require(body.has("to") && body.has("text") && body.keys().asSequence().all { it in setOf("to", "text", "subscriptionId") }) { "Supply to, text and an optional subscriptionId" }
        val requestedSim = if (body.has("subscriptionId")) body.get("subscriptionId") as? Int
            ?: throw IllegalArgumentException("subscriptionId must be an integer") else null
        require(requestedSim == null || requestedSim > 0) { "subscriptionId must be a positive integer" }
        val to = body.get("to") as? String ?: throw IllegalArgumentException("to must be a string")
        val text = body.get("text") as? String ?: throw IllegalArgumentException("text must be a string")
        require(to.matches(Regex("\\+[1-9][0-9]{6,14}"))) { "to must be an international phone number, for example +447700900000" }
        require(text.isNotBlank() && text.length <= 2000) { "text must contain 1 to 2000 characters" }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest("${requestedSim ?: "default"}\u0000$to\u0000$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
        SmsStore(context).use { store ->
            // Check persisted submissions before permissions/SIM checks: a retry never resends an SMS.
            val existing = store.readableDatabase.query("requests", arrayOf("id", "fingerprint"), "request_key = ?", arrayOf(key), null, null, null).use {
                if (!it.moveToFirst()) null else {
                    if (it.getString(1) != fingerprint) return error(Response.Status.CONFLICT, "This Idempotency-Key was already used with a different SMS")
                    store.get(it.getString(0))
                }
            }
            if (existing != null) return json(Response.Status.ACCEPTED, existing)
            requirePermission(Manifest.permission.SEND_SMS)
            val subscription = requestedSim ?: SubscriptionManager.getDefaultSmsSubscriptionId()
            if (!SubscriptionManager.isValidSubscriptionId(subscription)) return error(Response.Status.CONFLICT, "Supply subscriptionId or select a default SMS SIM in Android settings")
            if (requestedSim != null) {
                val active = PhoneInfo.subscriptions(context)
                require((0 until active.length()).any { active.getJSONObject(it).getInt("subscriptionId") == subscription }) { "The selected SIM is not active" }
            }
            val manager = context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscription)
            val parts = manager.divideMessage(text)
            val (request, fresh) = try { store.reserve(key, fingerprint, parts.size, subscription) }
                catch (_: IllegalArgumentException) { return error(Response.Status.CONFLICT, "This Idempotency-Key was already used with a different SMS") }
            val id = request.getString("id")
            if (fresh) {
                try {
                    val callbacks = ArrayList(parts.mapIndexed { part, _ ->
                        val intent = Intent(context, SmsSentReceiver::class.java)
                            .setData(Uri.parse("phoneapi://sms/$id/$part"))
                            .putExtra("id", id).putExtra("part", part)
                        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                    })
                    manager.sendMultipartTextMessage(to, null, parts, callbacks, null)
                    store.submitted(id)
                } catch (_: Exception) { store.failed(id, SmsManager.RESULT_ERROR_GENERIC_FAILURE) }
            }
            return json(Response.Status.ACCEPTED, store.get(id)!!)
        }
    }

    private fun method(allowed: String) = error(Response.Status.METHOD_NOT_ALLOWED, "Use $allowed").apply { addHeader("Allow", allowed) }
    private fun error(status: Response.Status, message: String) = json(status, JSONObject().put("error", message))
    private fun json(status: Response.Status, value: JSONObject): Response = newFixedLengthResponse(status, "application/json; charset=utf-8", value.toString()).apply {
        addHeader("Cache-Control", "no-store")
        addHeader("X-Content-Type-Options", "nosniff")
        closeConnection(true)
    }
}
