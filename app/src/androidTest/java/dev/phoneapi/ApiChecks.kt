package dev.phoneapi

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

class ApiChecks : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle().apply {
            putString("class", "dev.phoneapi.ApiChecks")
            putString("test", "smsRequestSafety")
            putInt("numtests", 1)
            putInt("current", 1)
        }
        sendStatus(1, result)
        try {
            SmsStore(targetContext, null).use { store ->
                val (first, fresh) = store.reserve("same-key", "original", 2, 1)
                check(fresh)
                val id = first.getString("id")
                val (retry, repeated) = store.reserve("same-key", "original", 2, 1)
                check(!repeated && retry.getString("id") == id)
                check(retry.getInt("subscriptionId") == 1)
                check(runCatching { store.reserve("same-key", "different", 2, 4) }.exceptionOrNull() is IllegalArgumentException)
                store.receipt(id, 0, Activity.RESULT_OK)
                store.receipt(id, 0, Activity.RESULT_OK)
                check(store.get(id)!!.getInt("sentParts") == 1)
                store.receipt(id, 1, Activity.RESULT_OK)
                store.submitted(id)
                check(store.get(id)!!.getString("state") == "sent")
                val (failed, _) = store.reserve("failure-key", "failure", 2, 4)
                val failureId = failed.getString("id")
                store.receipt(failureId, 0, 1)
                store.receipt(failureId, 1, Activity.RESULT_OK)
                check(store.get(failureId)!!.getString("state") == "failed")
                check(store.get("missing") == null)
            }
            check(PhoneFeatures.e164("(202) 555-0123", "US") == "+12025550123")
            check(PhoneFeatures.e164("+44 7700 900000", "GB") == "+447700900000")
            check(PhoneFeatures.e164("Example sender", "GB") == null)
            check(runCatching { PhoneFeatures.requireNumber("07700900000") }.isFailure)
            check(runCatching { Webhooks.validateUrl("https://example.com/events") }.isSuccess)
            check(runCatching { Webhooks.validateUrl("https://user:pass@example.com/events") }.isFailure)
            check(runCatching { Webhooks.validateUrl("file:///tmp/events") }.isFailure)
            EventStore(targetContext, null).use { store ->
                repeat(501) { index -> store.add(org.json.JSONObject().put("id", "event-$index").put("createdAt", System.currentTimeMillis() + index).put("type", "test")) }
                check(store.prune() == 1 && store.count() == 500)
                check(store.next()!!.getString("id") == "event-1")
                store.remove("event-1")
                check(store.count() == 499)
                store.clear(); check(store.next() == null)
                store.add(org.json.JSONObject().put("id", "expired").put("createdAt", 0))
                check(store.prune() == 1)
            }
            // Use the instrumentation app's preferences, never the phone API's saved key.
            val keyPrefs = context.getSharedPreferences("api", android.content.Context.MODE_PRIVATE)
            check(keyPrefs.edit().remove("token").commit())
            try {
                val firstKey = MainActivity.token(context)
                check(firstKey.length == 43)
                check(android.util.Base64.decode(firstKey, android.util.Base64.URL_SAFE).size == 32)
                check(MainActivity.token(context) == firstKey)
                val nextKey = MainActivity.token(context, replace = true)
                check(nextKey != firstKey && MainActivity.token(context) == nextKey)
                check(keyPrefs.edit().putString("token", "saved-test-key").commit())
                check(MainActivity.token(context) == "saved-test-key")
            } finally {
                check(keyPrefs.edit().remove("token").commit())
            }
            checkKeyboardInsets()
            result.putString("stream", "PASS: SMS request safety, E.164 normalisation, webhook validation/queue, random key persistence and keyboard layout. No SMS sent.\n")
            sendStatus(0, result)
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.stackTraceToString()}\n")
            result.putString("stack", e.stackTraceToString())
            sendStatus(-2, result)
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun checkKeyboardInsets() {
        val activity = startActivitySync(android.content.Intent(targetContext, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        fun views(view: android.view.View): Sequence<android.view.View> = sequence {
            yield(view)
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
        }
        val root = activity.window.decorView
        try {
            runOnMainSync {
                val field = views(root).filterIsInstance<android.widget.EditText>().single { it.contentDescription == "API key" }
                val scroll = views(root).filterIsInstance<android.widget.ScrollView>().single()
                scroll.fullScroll(android.view.View.FOCUS_DOWN)
                field.requestFocus()
                activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            var visible = false
            var details = "Keyboard did not open"
            repeat(25) {
                Thread.sleep(200)
                runOnMainSync {
                    val insets = root.rootWindowInsets
                    if (insets.isVisible(android.view.WindowInsets.Type.ime())) {
                        val save = views(root).filterIsInstance<android.widget.Button>().single { it.text == "Save key" }
                        val field = views(root).filterIsInstance<android.widget.EditText>().single { it.contentDescription == "API key" }
                        val keyboardTop = root.height - insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                        val rect = android.graphics.Rect()
                        visible = field.hasFocus() && listOf(save, field).all { it.getGlobalVisibleRect(rect) && rect.height() == it.height && rect.bottom <= keyboardTop }
                        val fieldRect = android.graphics.Rect(); field.getGlobalVisibleRect(fieldRect)
                        val saveRect = android.graphics.Rect(); save.getGlobalVisibleRect(saveRect)
                        details = "focus=${field.hasFocus()}, window=${root.hasWindowFocus()}, field=$fieldRect/${field.height}, save=$saveRect/${save.height}, keyboardTop=$keyboardTop"
                    }
                }
                if (visible) return
            }
            check(visible) { "API key field and Save key must be fully visible above the keyboard: $details" }
        } finally {
            runOnMainSync {
                activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .hideSoftInputFromWindow(root.windowToken, 0)
                activity.finish()
            }
        }
    }
}
