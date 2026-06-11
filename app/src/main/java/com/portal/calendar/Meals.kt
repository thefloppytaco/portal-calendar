package com.portal.calendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Meal planning: a plan entry per {date, slot} (breakfast/lunch/dinner/snack)
 * holding free text and optionally a recipe reference, plus a simple recipe
 * box. Plan entries older than a week are pruned on write.
 */
object Meals {
    private const val PLAN = "mealplan.json"
    private const val RECIPES = "recipes.json"
    val SLOTS = listOf("breakfast", "lunch", "dinner", "snack")

    private fun dayFmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun statusJson(ctx: Context): String = JSONObject()
        .put("plan", Data.readArray(ctx, PLAN))
        .put("recipes", Data.readArray(ctx, RECIPES))
        .toString()

    fun mutate(ctx: Context, action: JSONObject): String {
        when (action.getString("action")) {
            "setMeal" -> {
                val date = action.getString("date")
                val slot = action.getString("slot")
                if (slot !in SLOTS) throw IllegalArgumentException("unknown meal slot")
                val text = action.optString("text").trim()
                val arr = Data.readArray(ctx, PLAN)
                for (i in arr.length() - 1 downTo 0) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("date") == date && o.optString("slot") == slot) arr.remove(i)
                }
                if (text.isNotEmpty()) {
                    val entry = JSONObject().put("date", date).put("slot", slot).put("text", text)
                    action.optString("recipeId").takeIf { it.isNotEmpty() }
                        ?.let { entry.put("recipeId", it) }
                    arr.put(entry)
                }
                prune(arr)
                Data.writeArray(ctx, PLAN, arr)
            }
            "addRecipe" -> {
                val title = action.getString("title").trim()
                if (title.isEmpty()) throw IllegalArgumentException("the recipe needs a name")
                val arr = Data.readArray(ctx, RECIPES)
                arr.put(JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("title", title)
                    .put("ingredients", action.optString("ingredients").trim())
                    .put("steps", action.optString("steps").trim()))
                Data.writeArray(ctx, RECIPES, arr)
            }
            "deleteRecipe" -> {
                val arr = Data.readArray(ctx, RECIPES)
                val id = action.getString("recipeId")
                for (i in arr.length() - 1 downTo 0)
                    if (arr.getJSONObject(i).optString("id") == id) arr.remove(i)
                Data.writeArray(ctx, RECIPES, arr)
            }
            else -> throw IllegalArgumentException("unknown action")
        }
        App.instance.notifyDataChanged()
        return statusJson(ctx)
    }

    fun recipe(ctx: Context, id: String?): JSONObject? {
        if (id.isNullOrEmpty()) return null
        val arr = Data.readArray(ctx, RECIPES)
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optString("id") == id) return r
        }
        return null
    }

    /** slot → entry for one yyyy-MM-dd date. */
    fun planFor(ctx: Context, date: String): Map<String, JSONObject> {
        val arr = Data.readArray(ctx, PLAN)
        val out = HashMap<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("date") == date) out[o.optString("slot")] = o
        }
        return out
    }

    private fun prune(arr: JSONArray) {
        val cutoff = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -7) }
        val cut = dayFmt().format(cutoff.time)
        for (i in arr.length() - 1 downTo 0)
            if (arr.getJSONObject(i).optString("date") < cut) arr.remove(i)
    }
}
