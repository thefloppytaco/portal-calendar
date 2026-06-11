package com.portal.calendar

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Two-way sync between family lists and Google Tasks, riding the same OAuth
 * client as Google Calendar (the Tasks scope is requested at sign-in; accounts
 * connected before Tasks support must disconnect/reconnect once).
 *
 * Model: a family list may be linked to a Google task list (list.gtasksId);
 * each synced item carries gtaskId + gdone (Google's status at last sync) so
 * each side's changes are detected independently. Local deletions are queued
 * on the list (deletedGtaskIds) and pushed on the next pass. When both sides
 * changed the same item, Google wins.
 */
object GoogleTasks {
    private const val API = "https://tasks.googleapis.com/tasks/v1"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_T = "application/json; charset=utf-8".toMediaType()

    /** Links [listId] to a same-named Google task list (created if needed). */
    fun link(ctx: Context, listId: String) {
        if (!GoogleCal.isConnected(ctx))
            throw IllegalArgumentException("connect Google first (Settings → Two-way sync)")
        val lists = Data.readArray(ctx, "lists.json")
        val list = find(lists, listId)
        val name = list.optString("name")
        val existing = taskLists(ctx).firstOrNull { it.second.equals(name, true) }
        val gid = existing?.first ?: run {
            val resp = call(ctx, "POST", "$API/users/@me/lists",
                JSONObject().put("title", name).toString())
            resp.getString("id")
        }
        list.put("gtasksId", gid)
        Data.writeArray(ctx, "lists.json", lists)
        syncAll(ctx)
    }

    fun unlink(ctx: Context, listId: String) {
        val lists = Data.readArray(ctx, "lists.json")
        val list = find(lists, listId)
        list.remove("gtasksId")
        list.remove("deletedGtaskIds")
        val items = list.optJSONArray("items") ?: JSONArray()
        for (i in 0 until items.length()) {
            items.getJSONObject(i).remove("gtaskId")
            items.getJSONObject(i).remove("gdone")
        }
        Data.writeArray(ctx, "lists.json", lists)
        App.instance.notifyDataChanged()
    }

    /** Full reconcile of every linked list. Background threads only. */
    @Synchronized
    fun syncAll(ctx: Context) {
        if (!GoogleCal.isConnected(ctx)) return
        val lists = Data.readArray(ctx, "lists.json")
        var changed = false
        for (li in 0 until lists.length()) {
            val list = lists.getJSONObject(li)
            val gid = list.optString("gtasksId")
            if (gid.isEmpty()) continue
            try {
                if (syncList(ctx, list, gid)) changed = true
            } catch (e: Exception) {
                android.util.Log.w("PortalGTasks", "sync failed for ${list.optString("name")}", e)
            }
        }
        if (changed) {
            Data.writeArray(ctx, "lists.json", lists)
            App.instance.notifyDataChanged()
        } else {
            Data.writeArray(ctx, "lists.json", lists) // persists new gtaskIds/gdone
        }
    }

    private fun syncList(ctx: Context, list: JSONObject, gid: String): Boolean {
        var changed = false
        val items = list.optJSONArray("items") ?: JSONArray().also { list.put("items", it) }

        // 1. Push queued deletions.
        val deleted = list.optJSONArray("deletedGtaskIds") ?: JSONArray()
        for (i in 0 until deleted.length()) {
            runCatching { call(ctx, "DELETE", "$API/lists/$gid/tasks/${deleted.getString(i)}", null) }
        }
        list.remove("deletedGtaskIds")

        // 2. Fetch the remote state (paginated).
        val remote = HashMap<String, JSONObject>()
        var pageToken: String? = null
        do {
            val url = "$API/lists/$gid/tasks?showCompleted=true&showHidden=true&maxResults=100" +
                (pageToken?.let { "&pageToken=$it" } ?: "")
            val page = call(ctx, "GET", url, null)
            val arr = page.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                if (!t.optBoolean("deleted")) remote[t.getString("id")] = t
            }
            pageToken = page.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)

        // 3. Push items Google hasn't seen.
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.optString("gtaskId").isNotEmpty()) continue
            val done = item.optBoolean("done")
            val created = call(ctx, "POST", "$API/lists/$gid/tasks", JSONObject()
                .put("title", item.optString("text"))
                .put("status", if (done) "completed" else "needsAction").toString())
            item.put("gtaskId", created.getString("id"))
            item.put("gdone", done)
            // The remote snapshot predates this insert — without this, step 4
            // would treat the brand-new task as deleted-on-Google.
            remote[created.getString("id")] = created
        }

        // 4. Reconcile shared items.
        for (i in items.length() - 1 downTo 0) {
            val item = items.getJSONObject(i)
            val gtaskId = item.optString("gtaskId")
            if (gtaskId.isEmpty()) continue
            val g = remote.remove(gtaskId)
            if (g == null) { // deleted on Google → delete locally
                items.remove(i); changed = true; continue
            }
            val gDoneNow = g.optString("status") == "completed"
            val gDoneLast = item.optBoolean("gdone")
            val localDone = item.optBoolean("done")
            if (gDoneNow != gDoneLast) {
                if (localDone != gDoneNow) { item.put("done", gDoneNow); changed = true }
            } else if (localDone != gDoneNow) {
                call(ctx, "PATCH", "$API/lists/$gid/tasks/$gtaskId", JSONObject()
                    .put("status", if (localDone) "completed" else "needsAction").toString())
            }
            item.put("gdone", item.optBoolean("done"))
            val gTitle = g.optString("title").trim()
            if (gTitle.isNotEmpty() && gTitle != item.optString("text")) {
                item.put("text", gTitle); changed = true // Google wins titles
            }
        }

        // 5. Pull tasks created on Google.
        for ((id, g) in remote) {
            val title = g.optString("title").trim()
            if (title.isEmpty()) continue
            items.put(JSONObject()
                .put("id", java.util.UUID.randomUUID().toString())
                .put("text", title)
                .put("done", g.optString("status") == "completed")
                .put("gtaskId", id)
                .put("gdone", g.optString("status") == "completed"))
            changed = true
        }
        return changed
    }

    private fun taskLists(ctx: Context): List<Pair<String, String>> {
        val resp = call(ctx, "GET", "$API/users/@me/lists?maxResults=100", null)
        val arr = resp.optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.getString("id") to o.optString("title")
        }
    }

    private fun call(ctx: Context, method: String, url: String, body: String?): JSONObject {
        val b = Request.Builder().url(url)
            .header("Authorization", "Bearer " + GoogleCal.bearer(ctx))
        when (method) {
            "GET" -> b.get()
            "DELETE" -> b.delete()
            else -> b.method(method, (body ?: "{}").toRequestBody(JSON_T))
        }
        client.newCall(b.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.code == 403 || resp.code == 401)
                throw IllegalArgumentException(
                    "Google didn't allow Tasks access — disconnect and reconnect Google (Settings) to grant the Tasks permission")
            if (resp.code !in 200..299)
                throw IllegalArgumentException("Google Tasks error HTTP ${resp.code}")
            return if (text.isEmpty()) JSONObject() else JSONObject(text)
        }
    }

    private fun find(lists: JSONArray, listId: String): JSONObject {
        for (i in 0 until lists.length()) {
            val l = lists.getJSONObject(i)
            if (l.optString("id") == listId) return l
        }
        throw IllegalArgumentException("list not found")
    }
}
