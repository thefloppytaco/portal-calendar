package com.portal.calendar

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The board's Lists tab: a rail of lists on the left, the selected list's
 * items on the right with big tap-to-check rows, and inline inputs for adding
 * lists/items right on the touchscreen.
 */
class ListsTab(
    private val ctx: Context,
    private val gate: (action: () -> Unit) -> Unit = { it() }
) {

    private var selectedId: String? = null
    private lateinit var rail: LinearLayout
    private lateinit var itemsBox: LinearLayout
    private lateinit var itemsTitle: TextView
    private lateinit var clearDoneBtn: TextView
    private lateinit var itemInput: EditText

    val view: LinearLayout = build()

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    private fun build(): LinearLayout {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(22), dp(18), dp(22), dp(16))
        }

        // Left rail: the lists ------------------------------------------------
        val railCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        railCol.addView(TextView(ctx).apply {
            text = "LISTS"
            textSize = 13f
            setTextColor(ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.14f
        }, lp(bottom = dp(10)))
        rail = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        railCol.addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(rail, FrameLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val newListInput = EditText(ctx).apply {
            hint = "+ New list"
            textSize = 15f
            setTextColor(INK)
            setHintTextColor(FAINT)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = box()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnEditorActionListener { v, _, _ ->
                val name = text.toString().trim()
                if (name.isNotEmpty()) {
                    runCatching {
                        FamilyLists.mutate(ctx, JSONObject().put("action", "addList").put("name", name))
                    }
                    setText("")
                    hideKeyboard(v)
                }
                true
            }
        }
        railCol.addView(newListInput, lp(top = dp(8)))
        root.addView(railCol, LinearLayout.LayoutParams(dp(250), MATCH))

        // Right: items of the selected list -----------------------------------
        val itemsCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 16)
            elevation = dp(2).toFloat()
            setPadding(dp(20), dp(16), dp(20), dp(14))
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        itemsTitle = TextView(ctx).apply {
            textSize = 20f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        header.addView(itemsTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
        clearDoneBtn = pill("Clear done") {
            selectedId?.let {
                runCatching { FamilyLists.mutate(ctx, JSONObject().put("action", "clearDone").put("listId", it)) }
            }
        }
        header.addView(clearDoneBtn)
        header.addView(pill("Delete list") {
            gate {
                selectedId?.let {
                    runCatching { FamilyLists.mutate(ctx, JSONObject().put("action", "deleteList").put("listId", it)) }
                    selectedId = null
                }
            }
        })
        itemsCol.addView(header, lp(bottom = dp(10)))

        itemsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        itemsCol.addView(ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(itemsBox, FrameLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))

        itemInput = EditText(ctx).apply {
            hint = "+ Add an item"
            textSize = 16f
            setTextColor(INK)
            setHintTextColor(FAINT)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = box()
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setOnEditorActionListener { v, _, _ ->
                val text = text.toString().trim()
                val id = selectedId
                if (text.isNotEmpty() && id != null) {
                    runCatching {
                        FamilyLists.mutate(ctx, JSONObject()
                            .put("action", "addItem").put("listId", id).put("text", text))
                    }
                    setText("")
                }
                true
            }
        }
        itemsCol.addView(itemInput, lp(top = dp(10)))

        root.addView(itemsCol, LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = dp(18) })
        return root
    }

    fun render() {
        val lists = JSONArray(FamilyLists.json(ctx))
        if (selectedId == null || (0 until lists.length()).none {
                lists.getJSONObject(it).optString("id") == selectedId
            }) {
            selectedId = if (lists.length() > 0) lists.getJSONObject(0).optString("id") else null
        }

        rail.removeAllViews()
        for (i in 0 until lists.length()) {
            val l = lists.getJSONObject(i)
            val id = l.optString("id")
            val items = l.getJSONArray("items")
            val open = (0 until items.length()).count { !items.getJSONObject(it).optBoolean("done") }
            val selected = id == selectedId
            rail.addView(TextView(ctx).apply {
                text = l.optString("name") + if (open > 0) "   $open" else ""
                textSize = 16f
                setTextColor(if (selected) Color.WHITE else INK)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = rounded(if (selected) ACCENT else CARD, 12)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener { selectedId = id; render() }
            }, lp(bottom = dp(8)))
        }

        itemsBox.removeAllViews()
        if (selectedId == null) {
            itemsTitle.text = "No lists yet"
            clearDoneBtn.visibility = View.GONE
            itemInput.visibility = View.GONE
            itemsBox.addView(TextView(ctx).apply {
                text = "Create one on the left, or from your phone"
                textSize = 15f
                setTextColor(MUTED)
            })
            return
        }
        clearDoneBtn.visibility = View.VISIBLE
        itemInput.visibility = View.VISIBLE

        val list = (0 until lists.length()).map { lists.getJSONObject(it) }
            .first { it.optString("id") == selectedId }
        itemsTitle.text = list.optString("name")
        val items = list.getJSONArray("items")
        val sorted = (0 until items.length()).map { items.getJSONObject(it) }
            .sortedBy { it.optBoolean("done") }
        if (sorted.isEmpty()) {
            itemsBox.addView(TextView(ctx).apply {
                text = "Nothing here yet"
                textSize = 15f
                setTextColor(MUTED)
                setPadding(0, dp(8), 0, 0)
            })
        }
        for (item in sorted) {
            val done = item.optBoolean("done")
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(10), dp(6), dp(10))
                setOnClickListener {
                    runCatching {
                        FamilyLists.mutate(ctx, JSONObject()
                            .put("action", "toggleItem")
                            .put("listId", selectedId).put("itemId", item.optString("id")))
                    }
                }
                setOnLongClickListener {
                    runCatching {
                        FamilyLists.mutate(ctx, JSONObject()
                            .put("action", "deleteItem")
                            .put("listId", selectedId).put("itemId", item.optString("id")))
                    }
                    true
                }
            }
            row.addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (done) setColor(ACCENT) else {
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(2), FAINT)
                    }
                }
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(14) })
            row.addView(TextView(ctx).apply {
                text = item.optString("text")
                textSize = 17f
                setTextColor(if (done) FAINT else INK)
                paintFlags = if (done) paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                             else paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            itemsBox.addView(row)
        }
    }

    // ------------------------------------------------------------ helpers

    private fun pill(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 13f
        setTextColor(MUTED)
        background = rounded(PILL, 14)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun box() = GradientDrawable().apply {
        setColor(0xFFFBFAF6.toInt())
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), 0xFFDDD8CC.toInt())
    }

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = top; bottomMargin = bottom }

    private fun hideKeyboard(v: View) {
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(v.windowToken, 0)
    }

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private val CARD = Palette.CARD
        private val PILL = Palette.PILL
        private val INK = Palette.INK
        private val MUTED = Palette.MUTED
        private val FAINT = Palette.FAINT
        private val ACCENT = Palette.ACCENT
    }
}
