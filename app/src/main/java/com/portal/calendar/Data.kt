package com.portal.calendar

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Tiny JSON-file persistence for the family-hub data (members, lists, chores,
 * meals, …). Atomic writes (tmp + rename) so a mid-write crash never corrupts
 * a file; synchronized because mutations come from both the UI thread and the
 * config server's worker threads.
 */
object Data {
    @Synchronized
    fun readArray(ctx: Context, name: String): JSONArray = try {
        JSONArray(File(ctx.filesDir, name).readText())
    } catch (e: Exception) {
        JSONArray()
    }

    @Synchronized
    fun writeArray(ctx: Context, name: String, arr: JSONArray) {
        val tmp = File(ctx.filesDir, "$name.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(File(ctx.filesDir, name))
    }
}
