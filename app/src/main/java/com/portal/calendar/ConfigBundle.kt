package com.portal.calendar

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * Whole-Portal config export/import — a one-paste clone of this device onto
 * another. Captures the single "config" SharedPreferences (feeds, members'
 * settings, features, layout, AND credentials: Gemini key, iCloud password,
 * Google refresh token) plus every family-data JSON file.
 *
 * The code therefore contains secrets — the page warns the user to treat it
 * like a password. Volatile bits (the short-lived Google access token, the
 * weather + feed caches) are skipped; they regenerate on the new device.
 */
object ConfigBundle {
    private const val MAGIC = "PORTALHUB1:"
    private const val PREFS = "config"

    // Prefs that are caches/short-lived — re-derived on the target device.
    private val SKIP_PREFS = setOf("g_access", "g_token_exp")

    private val DATA_FILES = listOf(
        "members.json", "lists.json", "chores.json", "chore_done.json",
        "star_goals.json", "chore_history.json", "mealplan.json",
        "recipes.json", "magic_done.json")

    fun export(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pj = JSONObject()
        for ((k, v) in prefs.all) {
            if (k in SKIP_PREFS || v == null) continue
            // Type-tagged so import restores the exact SharedPreferences type.
            val (t, value) = when (v) {
                is Boolean -> "b" to v
                is Int -> "i" to v
                is Long -> "l" to v
                is Float -> "f" to v.toDouble()
                else -> "s" to v.toString()
            }
            pj.put(k, JSONObject().put("t", t).put("v", value))
        }
        val fj = JSONObject()
        for (name in DATA_FILES) {
            val f = File(ctx.filesDir, name)
            if (f.exists()) fj.put(name, f.readText())
        }
        val root = JSONObject().put("v", 1).put("prefs", pj).put("files", fj)
        return MAGIC + Base64.encodeToString(
            root.toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP)
    }

    /** Replaces this device's config with the bundle. Throws on a bad code. */
    fun import(ctx: Context, code: String) {
        val trimmed = code.trim()
        val payload = trimmed.removePrefix(MAGIC)
        if (payload == trimmed) // no prefix found
            throw IllegalArgumentException("that doesn't look like a PortalHub backup code")
        val json = try {
            String(Base64.decode(payload, Base64.NO_WRAP), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalArgumentException("the backup code is corrupted or incomplete")
        }
        val root = JSONObject(json)
        val prefsIn = root.optJSONObject("prefs")
            ?: throw IllegalArgumentException("the backup code is missing its settings")

        // VALIDATE EVERYTHING before touching the device — a malformed bundle
        // must not clear prefs and then throw mid-restore, half-bricking the
        // setup. Parse all prefs + file payloads up front; only apply if clean.
        val parsedPrefs = ArrayList<Triple<String, String, Any>>() // key, type, value
        try {
            for (k in prefsIn.keys()) {
                val e = prefsIn.getJSONObject(k)
                val t = e.optString("t")
                val v: Any = when (t) {
                    "b" -> e.getBoolean("v")
                    "i" -> e.getInt("v")
                    "l" -> e.getLong("v")
                    "f" -> e.getDouble("v")
                    else -> e.getString("v")
                }
                parsedPrefs.add(Triple(k, t, v))
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("the backup code's settings are malformed — nothing was changed")
        }
        val parsedFiles = ArrayList<Pair<String, String>>()
        root.optJSONObject("files")?.let { files ->
            try {
                for (name in files.keys()) {
                    if (name !in DATA_FILES) continue // ignore anything unexpected
                    parsedFiles.add(name to files.getString(name))
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("the backup code's data is malformed — nothing was changed")
            }
        }

        // Apply (validated): replace config prefs wholesale, then data files.
        val editor = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
        for ((k, t, v) in parsedPrefs) when (t) {
            "b" -> editor.putBoolean(k, v as Boolean)
            "i" -> editor.putInt(k, v as Int)
            "l" -> editor.putLong(k, v as Long)
            "f" -> editor.putFloat(k, (v as Double).toFloat())
            else -> editor.putString(k, v as String)
        }
        editor.commit()
        for ((name, text) in parsedFiles) Data.writeRaw(ctx, name, text)
    }
}
