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
 *
 * Concurrency: the reconcile runs against a SNAPSHOT while the family keeps
 * tapping the board, so its results are applied as per-item DELTAS to a fresh
 * read under [Data.mutate] — never by writing the stale snapshot back. Edits
 * made during the network window survive untouched and converge next pass.
 */
object GoogleTasks {
    private const val API = "https://tasks.googleapis.com/tasks/v1"
    private const val FILE = "lists.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_T = "application/json; charset=utf-8".toMediaType()

    /** Everything one syncList pass wants to change, keyed for a fresh merge. */
    private class ListResult(val listId: String) {
        val deletesPushed = HashSet<String>()          // queue entries to clear
        val patches = HashMap<String, JSONObject>()    // local item id → fields to set
        val removeByGtaskId = HashSet<String>()        // deleted on Google
        val newRemoteItems = ArrayList<JSONObject>()   // created on Google
    }

    /** Links [listId] to a same-named Google task list (created if needed). */
    fun link(ctx: Context, listId: String) {
        if (!GoogleCal.isConnected(ctx))
            throw IllegalArgumentException("connect Google first (Settings → Two-way sync)")
        val name = find(Data.readArray(ctx, FILE), listId).optString("name")
        val existing = taskLists(ctx).firstOrNull { it.second.equals(name, true) }
        val gid = existing?.first ?: run {
            val resp = call(ctx, "POST", "$API/users/@me/lists",
                JSONObject().put("title", name).toString())
            resp.getString("id")
        }
        Data.mutate(ctx, FILE) { lists ->
            findOrNull(lists, listId)?.put("gtasksId", gid)
        }
        syncAll(ctx)
    }

    fun unlink(ctx: Context, listId: String) {
        Data.mutate(ctx, FILE) { lists ->
            val list = findOrNull(lists, listId) ?: return@mutate
            list.remove("gtasksId")
            list.remove("deletedGtaskIds")
            val items = list.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                items.getJSONObject(i).remove("gtaskId")
                items.getJSONObject(i).remove("gdone")
            }
        }
        App.instance.notifyDataChanged()
    }

    /** Full reconcile of every linked list. Background threads only. */
    @Synchronized
    fun syncAll(ctx: Context) {
        if (!GoogleCal.isConnected(ctx)) return

        // Phase 1: network reconcile against a snapshot (no lock held).
        val snapshot = Data.readArray(ctx, FILE)
        val results = ArrayList<ListResult>()
        for (li in 0 until snapshot.length()) {
            val list = snapshot.getJSONObject(li)
            val gid = list.optString("gtasksId")
            if (gid.isEmpty()) continue
            try {
                results.add(syncList(ctx, list, gid))
            } catch (e: Exception) {
                android.util.Log.w("PortalGTasks", "sync failed for ${list.optString("name")}", e)
            }
        }
        if (results.isEmpty()) return

        // Phase 2: apply the deltas to FRESH state, atomically.
        var visible = false
        Data.mutate(ctx, FILE) { fresh ->
            for (r in results) {
                val list = findOrNull(fresh, r.listId) ?: continue
                if (list.optString("gtasksId").isEmpty()) continue // unlinked mid-sync
                // Clear only the deletions that actually reached Google; keep
                // failures AND anything queued during the network window.
                list.optJSONArray("deletedGtaskIds")?.let { q ->
                    val remaining = JSONArray()
                    for (i in 0 until q.length())
                        if (q.getString(i) !in r.deletesPushed) remaining.put(q.getString(i))
                    if (remaining.length() == 0) list.remove("deletedGtaskIds")
                    else list.put("deletedGtaskIds", remaining)
                }
                val items = list.optJSONArray("items") ?: JSONArray().also { list.put("items", it) }
                val seenGtaskIds = HashSet<String>()
                for (i in items.length() - 1 downTo 0) {
                    val item = items.getJSONObject(i)
                    if (item.optString("gtaskId") in r.removeByGtaskId) {
                        items.remove(i); visible = true; continue
                    }
                    r.patches[item.optString("id")]?.let { p ->
                        for (k in p.keys()) item.put(k, p.get(k))
                        if (p.has("done") || p.has("text")) visible = true
                    }
                    item.optString("gtaskId").takeIf { it.isNotEmpty() }?.let { seenGtaskIds.add(it) }
                }
                for (n in r.newRemoteItems) {
                    if (n.optString("gtaskId") !in seenGtaskIds) { items.put(n); visible = true }
                }
            }
        }
        if (visible) App.instance.notifyDataChanged()
    }

    private fun syncList(ctx: Context, list: JSONObject, gid: String): ListResult {
        val r = ListResult(list.optString("id"))
        val items = list.optJSONArray("items") ?: JSONArray()

        // 1. Push queued deletions; only successful ones leave the queue.
        val deleted = list.optJSONArray("deletedGtaskIds") ?: JSONArray()
        for (i in 0 until deleted.length()) {
            val id = deleted.getString(i)
            if (tryDelete(ctx, "$API/lists/$gid/tasks/$id")) r.deletesPushed.add(id)
        }

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
            patch(r, item).put("gtaskId", created.getString("id")).put("gdone", done)
            // The remote snapshot predates this insert — without this, step 4
            // would treat the brand-new task as deleted-on-Google.
            remote[created.getString("id")] = created
        }

        // 4. Reconcile shared items.
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val gtaskId = item.optString("gtaskId")
            if (gtaskId.isEmpty()) continue
            val g = remote.remove(gtaskId)
            if (g == null) { // deleted on Google → delete locally
                r.removeByGtaskId.add(gtaskId); continue
            }
            val gDoneNow = g.optString("status") == "completed"
            val gDoneLast = item.optBoolean("gdone")
            var localDone = item.optBoolean("done")
            if (gDoneNow != gDoneLast) {
                if (localDone != gDoneNow) { patch(r, item).put("done", gDoneNow); localDone = gDoneNow }
            } else if (localDone != gDoneNow) {
                call(ctx, "PATCH", "$API/lists/$gid/tasks/$gtaskId", JSONObject()
                    .put("status", if (localDone) "completed" else "needsAction").toString())
            }
            patch(r, item).put("gdone", localDone)
            val gTitle = g.optString("title").trim()
            if (gTitle.isNotEmpty() && gTitle != item.optString("text")) {
                patch(r, item).put("text", gTitle) // Google wins titles
            }
        }

        // 5. Pull tasks created on Google.
        for ((id, g) in remote) {
            val title = g.optString("title").trim()
            if (title.isEmpty()) continue
            r.newRemoteItems.add(JSONObject()
                .put("id", java.util.UUID.randomUUID().toString())
                .put("text", title)
                .put("done", g.optString("status") == "completed")
                .put("gtaskId", id)
                .put("gdone", g.optString("status") == "completed"))
        }
        return r
    }

    private fun patch(r: ListResult, item: JSONObject): JSONObject =
        r.patches.getOrPut(item.optString("id")) { JSONObject() }

    private fun taskLists(ctx: Context): List<Pair<String, String>> {
        val resp = call(ctx, "GET", "$API/users/@me/lists?maxResults=100", null)
        val arr = resp.optJSONArray("items") ?: JSONArray()
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.getString("id") to o.optString("title")
        }
    }

    /** DELETE that treats already-gone (404/410) as success. */
    private fun tryDelete(ctx: Context, url: String): Boolean = try {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer " + GoogleCal.bearer(ctx))
            .delete().build()
        client.newCall(req).execute().use { resp ->
            resp.body?.string()
            resp.code in 200..299 || resp.code == 404 || resp.code == 410
        }
    } catch (e: Exception) { false }

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

    private fun findOrNull(lists: JSONArray, listId: String): JSONObject? {
        for (i in 0 until lists.length()) {
            val l = lists.getJSONObject(i)
            if (l.optString("id") == listId) return l
        }
        return null
    }

    private fun find(lists: JSONArray, listId: String): JSONObject =
        findOrNull(lists, listId) ?: throw IllegalArgumentException("list not found")
}
