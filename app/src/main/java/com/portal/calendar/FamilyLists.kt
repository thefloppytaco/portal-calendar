package com.portal.calendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Shared family lists (groceries, to-dos, packing…). One JSON file; every
 * mutation comes through [mutate] as {action, …} from the board or the page,
 * and listeners re-render.
 */
object FamilyLists {
    private const val FILE = "lists.json"

    fun json(ctx: Context): String = Data.readArray(ctx, FILE).toString()

    fun mutate(ctx: Context, action: JSONObject): String {
        val out = Data.mutate(ctx, FILE) { arr -> apply(arr, action); arr.toString() }
        App.instance.notifyDataChanged()
        App.instance.kickTasksSync()
        return out
    }

    private fun apply(arr: JSONArray, action: JSONObject) {
        when (action.getString("action")) {
            "addList" -> {
                val name = action.getString("name").trim()
                if (name.isEmpty()) throw IllegalArgumentException("the list needs a name")
                arr.put(JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("name", name)
                    .put("items", JSONArray()))
            }
            "renameList" -> {
                val name = action.getString("name").trim()
                if (name.isEmpty()) throw IllegalArgumentException("the list needs a name")
                list(arr, action).put("name", name)
            }
            "deleteList" -> {
                val id = action.getString("listId")
                for (i in arr.length() - 1 downTo 0)
                    if (arr.getJSONObject(i).optString("id") == id) arr.remove(i)
            }
            "addItem" -> {
                val text = action.getString("text").trim()
                if (text.isEmpty()) throw IllegalArgumentException("the item is empty")
                list(arr, action).getJSONArray("items").put(JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("text", text)
                    .put("done", false))
            }
            "toggleItem" -> {
                val item = item(arr, action)
                item.put("done", !item.optBoolean("done"))
            }
            "deleteItem" -> {
                val l = list(arr, action)
                val items = l.getJSONArray("items")
                val id = action.getString("itemId")
                for (i in items.length() - 1 downTo 0) {
                    val item = items.getJSONObject(i)
                    if (item.optString("id") == id) {
                        queueRemoteDelete(l, item)
                        items.remove(i)
                    }
                }
            }
            "clearDone" -> {
                val l = list(arr, action)
                val items = l.getJSONArray("items")
                for (i in items.length() - 1 downTo 0) {
                    val item = items.getJSONObject(i)
                    if (item.optBoolean("done")) {
                        queueRemoteDelete(l, item)
                        items.remove(i)
                    }
                }
            }
            else -> throw IllegalArgumentException("unknown action")
        }
    }

    /** Remembers a synced item's Google id so the next sync pass deletes it remotely. */
    private fun queueRemoteDelete(list: JSONObject, item: JSONObject) {
        val gtaskId = item.optString("gtaskId")
        if (gtaskId.isEmpty() || list.optString("gtasksId").isEmpty()) return
        val q = list.optJSONArray("deletedGtaskIds")
            ?: JSONArray().also { list.put("deletedGtaskIds", it) }
        q.put(gtaskId)
    }

    private fun list(arr: JSONArray, action: JSONObject): JSONObject {
        val id = action.getString("listId")
        for (i in 0 until arr.length()) {
            val l = arr.getJSONObject(i)
            if (l.optString("id") == id) return l
        }
        throw IllegalArgumentException("list not found")
    }

    private fun item(arr: JSONArray, action: JSONObject): JSONObject {
        val items = list(arr, action).getJSONArray("items")
        val id = action.getString("itemId")
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            if (it.optString("id") == id) return it
        }
        throw IllegalArgumentException("item not found")
    }
}
