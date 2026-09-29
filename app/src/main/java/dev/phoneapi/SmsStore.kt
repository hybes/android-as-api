package dev.phoneapi

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

class SmsStore(context: Context, name: String? = "sms-requests.db") : SQLiteOpenHelper(context, name, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE requests (id TEXT PRIMARY KEY, request_key TEXT UNIQUE NOT NULL, fingerprint TEXT NOT NULL, state TEXT NOT NULL, parts INTEGER NOT NULL, sent INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, error INTEGER, subscription_id INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE receipts (request_id TEXT NOT NULL, part INTEGER NOT NULL, PRIMARY KEY(request_id, part))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE requests ADD COLUMN subscription_id INTEGER NOT NULL DEFAULT -1")
    }

    fun reserve(key: String, fingerprint: String, parts: Int, subscriptionId: Int): Pair<JSONObject, Boolean> {
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", id); put("request_key", key); put("fingerprint", fingerprint)
            put("state", "submitting"); put("parts", parts); put("created", System.currentTimeMillis())
            put("subscription_id", subscriptionId)
        }
        val inserted = writableDatabase.insertWithOnConflict("requests", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        readableDatabase.query("requests", null, "request_key = ?", arrayOf(key), null, null, null).use {
            check(it.moveToFirst()) { "Could not save SMS request" }
            require(it.getString(it.getColumnIndexOrThrow("fingerprint")) == fingerprint) { "This Idempotency-Key was already used with a different SMS" }
            return get(it.getString(it.getColumnIndexOrThrow("id")))!! to inserted
        }
    }

    fun get(id: String): JSONObject? = readableDatabase.query("requests", null, "id = ?", arrayOf(id), null, null, null).use {
        if (!it.moveToFirst()) return null
        JSONObject().apply {
            put("id", id); put("state", it.getString(it.getColumnIndexOrThrow("state")))
            put("parts", it.getInt(it.getColumnIndexOrThrow("parts")))
            put("sentParts", it.getInt(it.getColumnIndexOrThrow("sent")))
            put("createdAt", it.getLong(it.getColumnIndexOrThrow("created")))
            put("subscriptionId", it.getInt(it.getColumnIndexOrThrow("subscription_id")))
            val error = it.getColumnIndexOrThrow("error")
            if (!it.isNull(error)) put("errorCode", it.getInt(error))
        }
    }

    fun submitted(id: String) {
        writableDatabase.execSQL("UPDATE requests SET state = 'submitted' WHERE id = ? AND state = 'submitting'", arrayOf(id))
    }

    fun failed(id: String, result: Int) {
        writableDatabase.execSQL("UPDATE requests SET state = 'failed', error = ? WHERE id = ?", arrayOf<Any>(result, id))
    }

    fun receipt(id: String, part: Int, result: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val receipt = ContentValues().apply { put("request_id", id); put("part", part) }
            if (db.insertWithOnConflict("receipts", null, receipt, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                if (result == android.app.Activity.RESULT_OK) {
                    db.execSQL("UPDATE requests SET sent = sent + 1, state = CASE WHEN state = 'failed' THEN state WHEN sent + 1 = parts THEN 'sent' ELSE state END WHERE id = ?", arrayOf(id))
                } else failed(id, result)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}

class SmsSentReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        val id = intent.getStringExtra("id") ?: return
        val part = intent.getIntExtra("part", -1)
        if (part < 0) return
        SmsStore(context).use { it.receipt(id, part, resultCode) }
    }
}
