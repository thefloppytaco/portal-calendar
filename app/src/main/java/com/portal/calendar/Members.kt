package com.portal.calendar

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Member(val id: String, val name: String, val color: Int)

/**
 * The family members — the backbone that chores, stars, and color coding hang
 * off. Seeded from the calendar feeds on first use so existing setups get
 * sensible members for free; managed on the config page afterwards.
 */
object Members {
    private const val FILE = "members.json"

    fun all(ctx: Context): List<Member> {
        var arr = Data.readArray(ctx, FILE)
        if (arr.length() == 0) {
            val seeded = JSONArray()
            ConfigStore(ctx).feeds().filter { it.kind != "inbox" }.forEach { f ->
                seeded.put(JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("name", f.name)
                    .put("color", hex(f.color)))
            }
            if (seeded.length() > 0) {
                // Seed under the lock so two first-readers can't double-seed
                // members with mismatched UUIDs.
                arr = Data.mutate(ctx, FILE) { cur ->
                    if (cur.length() == 0) for (i in 0 until seeded.length()) cur.put(seeded.get(i))
                    cur
                }
            }
        }
        return (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            Member(
                o.optString("id").ifEmpty { return@mapNotNull null },
                o.optString("name").ifEmpty { return@mapNotNull null },
                parse(o.optString("color")))
        }
    }

    fun byId(ctx: Context, id: String?): Member? = all(ctx).find { it.id == id }

    fun json(ctx: Context): String {
        all(ctx) // trigger seeding
        return Data.readArray(ctx, FILE).toString()
    }

    /**
     * Members for the HTTP API — never ships the PINs themselves (any device
     * on the Wi-Fi can GET these; a kid could read a sibling's PIN). The page
     * only needs to know whether one is set.
     */
    fun publicJson(ctx: Context): String {
        all(ctx) // trigger seeding
        val arr = Data.readArray(ctx, FILE)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.put(JSONObject()
                .put("id", o.optString("id"))
                .put("name", o.optString("name"))
                .put("color", o.optString("color"))
                .put("hasPin", o.optString("pin").isNotEmpty()))
        }
        return out.toString()
    }

    /**
     * Full replace from the config page; keeps ids stable, assigns new ones.
     * A member arriving WITHOUT a "pin" key keeps their existing PIN (the
     * page no longer sees PINs, so it omits the key unless the field was
     * actually typed in; "" explicitly clears).
     */
    fun save(ctx: Context, json: String) {
        val incoming = JSONArray(json)
        // Read existing PINs, validate, and write — all under the one lock, so
        // a concurrent seed/save can't be clobbered (the read-then-write was
        // a lost-update window otherwise).
        Data.mutate(ctx, FILE) { current ->
            val currentPin = HashMap<String, String>()
            for (i in 0 until current.length()) {
                val o = current.getJSONObject(i)
                currentPin[o.optString("id")] = o.optString("pin")
            }
            val clean = JSONArray()
            for (i in 0 until incoming.length()) {
                val o = incoming.getJSONObject(i)
                val name = o.optString("name").trim()
                if (name.isEmpty()) throw IllegalArgumentException("member #${i + 1} has no name")
                parse(o.optString("color")) // validate
                val id = o.optString("id").ifEmpty { UUID.randomUUID().toString() }
                val pin = if (o.has("pin")) o.optString("pin").trim()
                          else currentPin[id] ?: ""
                if (pin.isNotEmpty() && !pin.matches(Regex("\\d{4}")))
                    throw IllegalArgumentException("$name's PIN must be 4 digits (or empty)")
                clean.put(JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("color", o.optString("color"))
                    .put("pin", pin))
            }
            // Replace contents in place (Data.mutate writes the same array back).
            while (current.length() > 0) current.remove(current.length() - 1)
            for (i in 0 until clean.length()) current.put(clean.get(i))
        }
        App.instance.notifyDataChanged()
    }

    fun parse(color: String): Int = try {
        Color.parseColor(color)
    } catch (e: Exception) {
        Color.parseColor("#4FA3E3")
    }

    private fun hex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)
}
