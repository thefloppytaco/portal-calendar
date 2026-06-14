package com.portal.calendar

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

/**
 * Tiny JSON-file persistence for the family-hub data (members, lists, chores,
 * meals, …). Writes are atomic (tmp + fsync + rename, so neither a crash nor a
 * power cut corrupts a file) and a file that still fails to parse is
 * quarantined to .corrupt instead of being silently reset.
 *
 * Concurrency: mutations come from the UI thread, the config server's worker
 * threads, AND the sync executor. A bare read-modify-write around
 * [readArray]/[writeArray] is NOT safe — two interleaved mutations lose one.
 * All mutations must go through [mutate], which holds the global lock across
 * the whole read-transform-write cycle. Keep transforms fast and NEVER do
 * network I/O inside one.
 */
object Data {
    @Synchronized
    fun readArray(ctx: Context, name: String): JSONArray = readImpl(ctx, name)

    @Synchronized
    fun writeArray(ctx: Context, name: String, arr: JSONArray) = writeImpl(ctx, name, arr)

    /** Atomic read-modify-write; returns whatever [transform] returns. */
    @Synchronized
    fun <T> mutate(ctx: Context, name: String, transform: (JSONArray) -> T): T {
        val arr = readImpl(ctx, name)
        val result = transform(arr)
        writeImpl(ctx, name, arr)
        return result
    }

    /** Atomic raw-text write (used by config import to restore files verbatim). */
    @Synchronized
    fun writeRaw(ctx: Context, name: String, text: String) {
        val tmp = File(ctx.filesDir, "$name.tmp")
        FileOutputStream(tmp).use { os ->
            os.write(text.toByteArray(Charsets.UTF_8)); os.fd.sync()
        }
        if (!tmp.renameTo(File(ctx.filesDir, name))) {
            tmp.copyTo(File(ctx.filesDir, name), overwrite = true); tmp.delete()
        }
    }

    private fun readImpl(ctx: Context, name: String): JSONArray {
        val f = File(ctx.filesDir, name)
        if (!f.exists()) return JSONArray()
        return try {
            JSONArray(f.readText())
        } catch (e: Exception) {
            // Don't silently wipe the family's data — keep the evidence.
            runCatching { f.renameTo(File(ctx.filesDir, "$name.corrupt")) }
            JSONArray()
        }
    }

    private fun writeImpl(ctx: Context, name: String, arr: JSONArray) {
        val tmp = File(ctx.filesDir, "$name.tmp")
        FileOutputStream(tmp).use { os ->
            os.write(arr.toString().toByteArray(Charsets.UTF_8))
            os.fd.sync() // durable before the rename, or power loss can zero the file
        }
        if (!tmp.renameTo(File(ctx.filesDir, name))) {
            // Same-dir rename shouldn't fail; fall back to a direct copy.
            tmp.copyTo(File(ctx.filesDir, name), overwrite = true)
            tmp.delete()
        }
    }
}
