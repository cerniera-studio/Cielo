package com.fergusonconsultancy.cielo

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** The family's small kit for real views: Jost words, one main action, champagne for what is chosen. */
object Kit {
    fun Context.dp(v: Float) = v * resources.displayMetrics.density

    fun Context.words(s: String, size: Float, colour: Int, weight: Int = 450, tracking: Float = 0f) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_DIP, size); setTextColor(colour)
        typeface = Type.sans(this@words, weight); letterSpacing = tracking
        includeFontPadding = false
    }

    fun Context.label(s: String) = words(s.uppercase(), 11f, Palette.MUTED, 600, 0.14f).apply {
        setPadding(0, dp(26f).toInt(), 0, dp(10f).toInt())
    }

    fun round(colour: Int, r: Float) = GradientDrawable().apply { setColor(colour); cornerRadius = r }

    fun Context.segments(options: List<String>, chosen: Int, pick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(Palette.RAISED, dp(24f))
            setPadding(dp(3f).toInt(), dp(3f).toInt(), dp(3f).toInt(), dp(3f).toInt())
        }
        val cells = ArrayList<TextView>()
        fun paint(sel: Int) = cells.forEachIndexed { i, t ->
            t.background = if (i == sel) round(Palette.CHAMPAGNE, dp(21f)) else null
            t.setTextColor(if (i == sel) Palette.GROUND else Palette.IVORY)
        }
        options.forEachIndexed { i, s ->
            val t = words(s, 14.5f, Palette.IVORY, 600).apply {
                gravity = Gravity.CENTER
                minHeight = dp(42f).toInt()
                setOnClickListener { paint(i); pick(i) }
            }
            cells += t
            row.addView(t, LinearLayout.LayoutParams(0, dp(42f).toInt(), 1f))
        }
        paint(chosen)
        return row
    }
}

/**
 * A sheet that rises from the foot of the screen (on the main screen, the right half) on one
 * crisp deceleration, and leaves the same way. Tap outside it, press back, or Done to close.
 */
@SuppressLint("ViewConstructor")
open class Sheet(context: Context, title: String) : FrameLayout(context) {
    private val scrim = View(context).apply { setBackgroundColor(0x99000000.toInt()); alpha = 0f }
    protected val panel = FrameLayout(context)
    protected val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    var onClosed: () -> Unit = {}
    var closing = false
        private set

    init {
        with(Kit) {
            addView(scrim, LayoutParams(-1, -1))
            scrim.setOnClickListener { close() }
            panel.background = GradientDrawable().apply {
                setColor(Palette.SURFACE)
                val r = context.dp(28f); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            panel.isClickable = true
            val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; isFillViewport = false }
            val pad = context.dp(24f).toInt()
            body.setPadding(pad, pad, pad, pad)
            val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(context.words(title, 22f, Palette.IVORY, 600), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(context.words("Done", 15f, Palette.CHAMPAGNE, 600).apply {
                setPadding(pad / 2, pad / 3, 0, pad / 3); setOnClickListener { close() }
            })
            body.addView(head)
            scroll.addView(body)
            panel.addView(scroll, LayoutParams(-1, -2))
            addView(panel, LayoutParams(-1, -2, Gravity.BOTTOM))
        }
    }

    fun place(wide: Boolean, bottomInset: Int) {
        val lp = panel.layoutParams as LayoutParams
        lp.width = if (wide) width / 2 else -1
        lp.gravity = Gravity.BOTTOM or Gravity.END
        panel.layoutParams = lp
        body.setPadding(body.paddingLeft, body.paddingTop, body.paddingRight, Kit.run { context.dp(24f).toInt() } + bottomInset)
    }

    fun open() {
        panel.post {
            panel.translationY = panel.height.toFloat()
            panel.animate().translationY(0f).setDuration(380).setInterpolator(Tween.EASE).start()
            scrim.animate().alpha(1f).setDuration(300).start()
        }
    }

    fun close() {
        if (closing) return
        closing = true
        hideKeyboard()
        panel.animate().translationY(panel.height.toFloat()).setDuration(260).setInterpolator(Tween.EASE).withEndAction {
            (parent as? ViewGroup)?.removeView(this); onClosed()
        }.start()
        scrim.animate().alpha(0f).setDuration(240).start()
    }

    protected fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }
}

class PlacesSheet(
    context: Context,
    private val places: () -> List<Place>,
    private val current: () -> Int,
    private val tempOf: (Place) -> String?,
    private val search: (String, (List<Place>?) -> Unit) -> Unit,
    private val add: (Place) -> Unit,
    private val pick: (Int) -> Unit,
    private val remove: (Int) -> Unit,
    private val locate: () -> Unit,
) : Sheet(context, "Places") {

    private val field = EditText(context)
    private val note: TextView = Kit.run { context.words("", 13.5f, Palette.MUTED, 450).apply { setPadding(0, context.dp(12f).toInt(), 0, 0); visibility = GONE } }
    private val results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val saved = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var asked = ""

    init {
        with(Kit) {
            val c = context
            field.apply {
                hint = "Add a town or city"
                setHintTextColor(Palette.FAINT); setTextColor(Palette.IVORY)
                typeface = Type.sans(c, 450); setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
                background = Kit.round(Palette.RAISED, c.dp(16f))
                setPadding(c.dp(18f).toInt(), 0, c.dp(18f).toInt(), 0)
                isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                backgroundTintList = null
                setOnEditorActionListener { _, a, ev ->
                    if (a == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { find(text.toString()); true } else false
                }
                // Search as you type, after a short pause: no need to find the Search key
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        pending?.let { handler.removeCallbacks(it) }
                        val q = s.toString()
                        pending = Runnable { find(q) }.also { handler.postDelayed(it, 380) }
                    }
                })
            }
            body.addView(field, LinearLayout.LayoutParams(-1, c.dp(54f).toInt()).apply { topMargin = c.dp(22f).toInt() })
            body.addView(note)
            body.addView(results)
            body.addView(c.words("Use my location", 15f, Palette.CHAMPAGNE, 600).apply {
                setPadding(0, c.dp(20f).toInt(), 0, c.dp(6f).toInt())
                setOnClickListener { locate() }
            })
            body.addView(c.label("Saved"))
            body.addView(saved)
        }
        render()
    }

    private fun find(q: String) {
        val s = q.trim()
        if (s == asked) return
        asked = s
        results.removeAllViews()
        if (s.length < 2) { note.visibility = GONE; return }
        note.text = "Searching"; note.visibility = VISIBLE
        search(s) { found ->
            if (asked != s) return@search
            results.removeAllViews()
            when {
                found == null -> note.text = "Search isn't reachable right now. Check the connection and try again."
                found.isEmpty() -> note.text = "No place found for “$s”. Try a nearby town or another spelling."
                else -> {
                    note.visibility = GONE
                    found.forEach { p -> results.addView(row(p.name, p.detail, "Add") { add(p); field.setText(""); hideKeyboard() }) }
                }
            }
        }
    }

    private fun row(title: String, detail: String, action: String?, chosen: Boolean = false, onAction: (() -> Unit)? = null, onTap: (() -> Unit)? = null): View = with(Kit) {
        val c = context
        val r = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = c.dp(60f).toInt()
        }
        val text = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        text.addView(c.words(title, 15.5f, if (chosen) Palette.CHAMPAGNE else Palette.IVORY, 600))
        if (detail.isNotEmpty()) text.addView(c.words(detail, 12.5f, Palette.MUTED, 500).apply { setPadding(0, c.dp(4f).toInt(), 0, 0) })
        r.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null) r.addView(c.words(action, 14f, if (onTap == null) Palette.CHAMPAGNE else Palette.MUTED, 600).apply {
            setPadding(c.dp(16f).toInt(), c.dp(14f).toInt(), 0, c.dp(14f).toInt())
            setOnClickListener { (onAction ?: onTap)?.invoke() }
        })
        if (onTap != null) r.setOnClickListener { onTap() } else if (onAction != null) r.setOnClickListener { onAction() }
        r
    }

    private fun row(title: String, detail: String, action: String, onAction: () -> Unit) = row(title, detail, action, false, onAction, null)

    fun render() {
        saved.removeAllViews()
        val list = places()
        list.forEachIndexed { i, p ->
            val detail = listOfNotNull(if (p.here) "Your location" else p.detail.takeIf { it.isNotEmpty() }, tempOf(p)).joinToString(" · ")
            saved.addView(row(p.name, detail, if (list.size > 1) "Remove" else null, i == current(),
                onAction = if (list.size > 1) ({ remove(i) }) else null, onTap = { pick(i) }))
        }
    }

    fun say(s: String) { note.text = s; note.visibility = if (s.isEmpty()) GONE else VISIBLE }
}

class SettingsSheet(context: Context, version: String, changed: () -> Unit) : Sheet(context, "Settings") {
    init {
        with(Kit) {
            with(Store) {
                val c = context
                body.addView(c.label("Temperature"))
                body.addView(c.segments(listOf("°C", "°F"), if (c.fahrenheit) 1 else 0) { c.fahrenheit = it == 1; changed() })
                body.addView(c.label("Wind"))
                val units = listOf("mph", "kmh", "ms")
                body.addView(c.segments(listOf("mph", "km/h", "m/s"), units.indexOf(c.windUnit).coerceAtLeast(0)) { c.windUnit = units[it]; changed() })
                body.addView(c.label("Motion"))
                body.addView(toggle("Concentrato", "Labels step back while you move fast, and a fast fold plays a shorter entrance.", c.concentrato) { c.concentrato = it; changed() })
                body.addView(toggle("Haptics", "A tick for each hour under your finger, and a click as the sun seats.", c.haptics) { c.haptics = it; changed() })
                body.addView(c.words("Weather data by Open-Meteo.com, licensed CC BY 4.0. Places are kept on this phone and nothing else is sent anywhere.", 12.5f, Palette.FAINT, 450).apply {
                    setPadding(0, c.dp(30f).toInt(), 0, 0); setLineSpacing(0f, 1.25f)
                })
                body.addView(c.words("Cielo $version · Ferguson Consultancy Designs", 12.5f, Palette.FAINT, 450).apply { setPadding(0, c.dp(8f).toInt(), 0, 0) })
            }
        }
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun toggle(title: String, detail: String, on: Boolean, set: (Boolean) -> Unit): View = with(Kit) {
        val c = context
        val r = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, c.dp(8f).toInt(), 0, c.dp(8f).toInt()) }
        val t = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        t.addView(c.words(title, 15.5f, Palette.IVORY, 600))
        t.addView(c.words(detail, 12.5f, Palette.MUTED, 500).apply { setPadding(0, c.dp(4f).toInt(), c.dp(12f).toInt(), 0); setLineSpacing(0f, 1.2f) })
        r.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        val s = Switch(c).apply {
            isChecked = on
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Palette.CHAMPAGNE, Palette.MUTED))
            trackTintList = ColorStateList(states, intArrayOf(alpha(Palette.CHAMPAGNE, 0.4f), Palette.RAISED))
            setOnCheckedChangeListener { _, v -> set(v) }
            contentDescription = title
        }
        r.addView(s)
        r.setOnClickListener { s.toggle() }
        r
    }
}
