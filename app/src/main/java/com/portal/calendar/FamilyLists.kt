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
    private const val UNDO_FILE = "lists-clear-undo.json"

    fun json(ctx: Context): String = Data.readArray(ctx, FILE).toString()

    /** Voice-friendly mutation for the one shared family shopping list. */
    fun shoppingCommand(ctx: Context, operation: String, text: String): String {
        val itemText = text.trim()
        if (itemText.isEmpty()) throw IllegalArgumentException("the shopping item is empty")
        val op = operation.trim().lowercase()
        var result = ""
        Data.mutate(ctx, FILE) { arr ->
            var list: JSONObject? = null
            for (i in 0 until arr.length()) {
                val candidate = arr.getJSONObject(i)
                val name = candidate.optString("name").lowercase()
                if (name.contains("grocer") || name.contains("shopping")) { list = candidate; break }
            }
            if (list == null) {
                list = JSONObject().put("id", UUID.randomUUID().toString())
                    .put("name", "Groceries").put("items", JSONArray())
                arr.put(list)
            }
            val items = list!!.getJSONArray("items")
            val existing = (0 until items.length()).map { items.getJSONObject(it) }
                .firstOrNull { it.optString("text").equals(itemText, ignoreCase = true) }
            when (op) {
                "add" -> {
                    if (existing == null) items.put(JSONObject().put("id", UUID.randomUUID().toString())
                        .put("text", itemText).put("done", false))
                    result = if (existing == null) "added" else "already_present"
                }
                "remove", "delete" -> {
                    if (existing != null) for (i in items.length() - 1 downTo 0)
                        if (items.getJSONObject(i).optString("id") == existing.optString("id")) {
                            queueRemoteDelete(list!!, existing); items.remove(i)
                        }
                    result = if (existing == null) "not_found" else "removed"
                }
                else -> throw IllegalArgumentException("operation must be add or remove")
            }
        }
        App.instance.notifyDataChanged()
        App.instance.kickTasksSync()
        return JSONObject().put("ok", true).put("operation", result).put("item", itemText).toString()
    }

    /** Adds an ordinary task to the single PortalHub-owned list with a member id. */
    fun addOwnedTask(ctx: Context, owner: String, text: String): String {
        val itemText = text.trim()
        if (itemText.isEmpty()) throw IllegalArgumentException("the task is empty")
        val normalized = owner.trim().lowercase()
        val member = Members.all(ctx).firstOrNull {
            it.name.lowercase() == normalized ||
                (normalized == "matt" && it.name.equals("Mathieu", true))
        } ?: throw IllegalArgumentException("unknown task owner: $owner")
        val out = Data.mutate(ctx, FILE) { arr ->
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }
                .firstOrNull { it.optString("name").equals("Tasks", true) && !it.optBoolean("archived") }
                ?: JSONObject().put("id", UUID.randomUUID().toString()).put("name", "Tasks").put("items", JSONArray()).also { arr.put(it) }
            list.getJSONArray("items").put(JSONObject().put("id", UUID.randomUUID().toString())
                .put("text", itemText).put("done", false).put("ownerId", member.id))
            arr.toString()
        }
        App.instance.notifyDataChanged(); App.instance.kickTasksSync()
        return out
    }

    /** Add recipe ingredients to the shared Groceries list, merging quantities instead of duplicating. */
    fun addRecipeIngredients(ctx: Context, ingredients: String): String {
        var added = 0; var merged = 0
        Data.mutate(ctx, FILE) { arr ->
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }
                .firstOrNull { it.optString("name").contains("grocer", true) || it.optString("name").contains("shopping", true) }
                ?: JSONObject().put("id", UUID.randomUUID().toString()).put("name", "Groceries").put("items", JSONArray()).also { arr.put(it) }
            val items = list.getJSONArray("items")
            ingredients.lines().map { it.trim().trimStart('-', '•', '*', ' ') }.filter { it.isNotEmpty() }.forEach { raw ->
                val parsed = IngredientLine.parse(raw)
                val existing = (0 until items.length()).map { items.getJSONObject(it) }
                    .firstOrNull { IngredientLine.sameBase(it.optString("text"), parsed.base) }
                if (existing == null) { items.put(JSONObject().put("id", UUID.randomUUID().toString()).put("text", parsed.display).put("done", false)); added++ }
                else {
                    val combined = IngredientLine.combine(existing.optString("text"), parsed)
                    if (combined != existing.optString("text")) { existing.put("text", combined); merged++ }
                }
            }
        }
        App.instance.notifyDataChanged(); App.instance.kickTasksSync()
        return JSONObject().put("added", added).put("merged", merged).toString()
    }

    private data class IngredientLine(val base: String, val amount: Double?, val unit: String?, val count: Int?, val optional: Boolean, val display: String) {
        companion object {
            private val paren = Regex("^(.+?)\\s*\\(([^)]+)\\)$")
            private val countRx = Regex("^(.+?)\\s+x(\\d+)$", RegexOption.IGNORE_CASE)
            private val leading = Regex("^((?:\\d+\\/\\d+|\\d+(?:\\.\\d+)?)(?:\\s+(?:lb|lbs|oz|kg|g|cups?|tbsp|tbsps|tablespoons?|tsp|tsps|teaspoons?|packets?|cans?|cloves?|pieces?|slices?|handfulls?|handfuls?))?)\\s+(.+)$", RegexOption.IGNORE_CASE)
            fun parse(raw: String): IngredientLine {
                var text = raw.trim()
                var optional = Regex("\\(optional\\)", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
                    text.contains(" optional", true)
                text = text.replace(Regex("\\s*\\(optional\\)", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\boptional\\b", RegexOption.IGNORE_CASE), "").trim()
                paren.matchEntire(text)?.let {
                    val note = it.groupValues[2].trim()
                    when {
                        note.startsWith("like ", true) -> Unit // keep descriptive note in the base
                        note.contains(" or ", true) -> text = it.groupValues[1].trim() // generalized alternative
                        else -> return quantity(it.groupValues[1], note, optional)
                    }
                }
                countRx.matchEntire(text)?.let { return IngredientLine(cleanBase(it.groupValues[1]), null, null, it.groupValues[2].toInt(), optional, "${cleanBase(it.groupValues[1])} x${it.groupValues[2]}") }
                leading.matchEntire(text)?.let { return quantity(it.groupValues[2], it.groupValues[1], optional) }
                return IngredientLine(cleanBase(text), null, null, null, optional, cleanBase(text) + if (optional) " (optional)" else "")
            }
            private fun quantity(base: String, amount: String, optional: Boolean): IngredientLine {
                val normalized = amount.trim().replace(Regex("^tablespoons?$", RegexOption.IGNORE_CASE), "tbsp").replace(Regex("^teaspoons?$", RegexOption.IGNORE_CASE), "tsp").replace(Regex("^handfuls?$", RegexOption.IGNORE_CASE), "handfulls")
                val m = Regex("^((?:\\d+\\s+)?(?:\\d+\\/\\d+|\\d+(?:\\.\\d+)?))(?:\\s+(.+))?$").matchEntire(normalized)
                val n = m?.groupValues?.get(1)?.let(::fraction); val unit = m?.groupValues?.get(2)?.trim()?.ifEmpty { null }
                val b = cleanBase(base)
                if (unit == null && n != null && n % 1.0 == 0.0 && b.contains(" - sliced", true))
                    return IngredientLine(b, null, null, n.toInt(), optional, "$b x${n.toInt()}")
                val shownUnit = unit?.let { canonicalUnit(it) }
                return IngredientLine(b, n, shownUnit, null, optional, "$b (${format(n ?: 0.0)}${shownUnit?.let { " $it" } ?: ""}${if (optional) " optional" else ""})")
            }
            private fun canonicalUnit(unit: String): String = when (unit.lowercase()) {
                "tablespoon", "tablespoons", "tbsp", "tbsps" -> "tbsp"
                "teaspoon", "teaspoons", "tsp", "tsps" -> "tsp"
                "handful", "handfuls", "handfull", "handfulls" -> "handfulls"
                else -> unit
            }
            private fun fraction(s: String): Double? = if (s.contains('/')) s.split('/').let { a -> a.getOrNull(0)?.toDoubleOrNull()?.div(a.getOrNull(1)?.toDoubleOrNull() ?: return null) } else s.toDoubleOrNull()
            private fun cleanBase(raw: String): String {
                var b = raw.trim().replace(Regex("\\s+"), " ")
                val parenAt = b.indexOf('(')
                val orAt = b.indexOf(" or ", ignoreCase = true)
                if (orAt >= 0 && (parenAt < 0 || orAt < parenAt)) b = b.substring(0, orAt)
                b = b.replace(Regex("^shredded\\s+", RegexOption.IGNORE_CASE), "")
                b = b.replace(Regex(",\\s*sliced$", RegexOption.IGNORE_CASE), " - sliced")
                b = b.trim().trimEnd(':', ',', '.')
                if (b.endsWith("s - sliced", true)) b = b.dropLast("s - sliced".length) + " - sliced"
                return b.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            fun sameBase(existing: String, base: String): Boolean = parse(existing).base.equals(base, true)
            fun combine(existingText: String, incoming: IngredientLine): String {
                val old = parse(existingText)
                if (old.count != null && incoming.count != null && old.base.equals(incoming.base, true)) return "${old.base} x${old.count + incoming.count}"
                if (!incoming.optional && incoming.amount != null && old.optional && old.amount == null && old.base.equals(incoming.base, true))
                    return "${incoming.display} (optional)"
                if (incoming.optional && incoming.amount != null && old.base.equals(incoming.base, true)) {
                    val opt = Regex("\\((\\d+(?:\\.\\d+)?(?:\\s+\\S+)?)\\s+optional\\)$", RegexOption.IGNORE_CASE).find(existingText)
                    if (opt != null) {
                        val parts = opt.groupValues[1].split(Regex("\\s+"), limit = 2)
                        val oldN = parts[0].toDoubleOrNull() ?: 0.0
                        val unit = parts.getOrNull(1)?.trim().orEmpty()
                        val n = oldN + incoming.amount
                        val value = if (n % 1.0 == 0.0) n.toInt().toString() else "%.2f".format(java.util.Locale.US, n).trimEnd('0').trimEnd('.')
                        return existingText.removeSuffix("(${opt.groupValues[1]} optional)") + "(${value}${if (unit.isNotEmpty()) " $unit" else ""} optional)"
                    }
                    if (old.amount != null && !old.optional) return "$existingText (${format(incoming.amount)}${incoming.unit?.let { " $it" } ?: ""} optional)"
                }
                if (old.amount != null && incoming.amount != null && old.base.equals(incoming.base, true) && old.unit.equals(incoming.unit, true)) {
                    val n = old.amount + incoming.amount
                    val value = if (n % 1.0 == 0.0) n.toInt().toString() else "%.2f".format(java.util.Locale.US, n).trimEnd('0').trimEnd('.')
                    val optionalSuffix = if (old.optional && incoming.optional) " optional" else ""
                    return "${old.base} (${value}${old.unit?.let { " $it" } ?: ""}$optionalSuffix)"
                }
                if (old.amount != null && incoming.amount == null && incoming.optional && old.base.equals(incoming.base, true)) return "$existingText (optional)"
                return existingText
            }
            private fun format(n: Double): String = when {
                n % 1.0 == 0.0 -> n.toInt().toString()
                kotlin.math.abs(n - 0.5) < 0.001 -> "1/2"
                kotlin.math.abs(n - 0.25) < 0.001 -> "1/4"
                kotlin.math.abs(n - 0.75) < 0.001 -> "3/4"
                else -> "%.2f".format(java.util.Locale.US, n).trimEnd('0').trimEnd('.')
            }
        }
    }

    fun mutate(ctx: Context, action: JSONObject): String {
        val out = Data.mutate(ctx, FILE) { arr ->
            when (action.optString("action")) {
                "clearDone" -> {
                    val l = list(arr, action); val removed = JSONArray(); val items = l.getJSONArray("items")
                    for (i in items.length() - 1 downTo 0) if (items.getJSONObject(i).optBoolean("done")) removed.put(items.getJSONObject(i))
                    Data.writeRaw(ctx, UNDO_FILE, JSONObject().put("listId", l.optString("id")).put("items", removed).toString())
                }
                "undoClearDone" -> {
                    val snap = Data.readObject(ctx, UNDO_FILE); val l = list(arr, action)
                    if (snap.optString("listId") == l.optString("id")) {
                        val saved = snap.optJSONArray("items") ?: JSONArray(); val items = l.getJSONArray("items")
                        for (i in saved.length() - 1 downTo 0) items.put(saved.getJSONObject(i))
                        Data.writeRaw(ctx, UNDO_FILE, JSONObject().toString())
                    }
                    return@mutate arr.toString()
                }
            }
            apply(arr, action); arr.toString()
        }
        FamilySync.pushIfSpoke(ctx, "lists", action.toString())
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
            "archiveList" -> list(arr, action).put("archived", action.optBoolean("archived", true))
            "setListOwner" -> list(arr, action).apply {
                action.optString("ownerId").takeIf { it.isNotBlank() }?.let { put("ownerId", it) }
                action.optString("owner").takeIf { it.isNotBlank() }?.let { put("owner", it.trim()) }
            }
            "addItem" -> {
                val text = action.getString("text").trim()
                if (text.isEmpty()) throw IllegalArgumentException("the item is empty")
                list(arr, action).getJSONArray("items").put(JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("text", text)
                    .put("done", false).apply {
                        action.optString("ownerId").takeIf { it.isNotBlank() }?.let { put("ownerId", it) }
                    })
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
