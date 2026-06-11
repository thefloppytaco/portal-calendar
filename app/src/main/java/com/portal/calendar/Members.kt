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
                Data.writeArray(ctx, FILE, seeded)
                arr = seeded
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

    /** Full replace from the config page; keeps ids stable, assigns new ones. */
    fun save(ctx: Context, json: String) {
        val incoming = JSONArray(json)
        val clean = JSONArray()
        for (i in 0 until incoming.length()) {
            val o = incoming.getJSONObject(i)
            val name = o.optString("name").trim()
            if (name.isEmpty()) throw IllegalArgumentException("member #${i + 1} has no name")
            parse(o.optString("color")) // validate
            clean.put(JSONObject()
                .put("id", o.optString("id").ifEmpty { UUID.randomUUID().toString() })
                .put("name", name)
                .put("color", o.optString("color")))
        }
        Data.writeArray(ctx, FILE, clean)
        App.instance.notifyDataChanged()
    }

    fun parse(color: String): Int = try {
        Color.parseColor(color)
    } catch (e: Exception) {
        Color.parseColor("#4FA3E3")
    }

    private fun hex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)
}
