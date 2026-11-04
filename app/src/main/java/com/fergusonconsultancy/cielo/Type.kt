package com.fergusonconsultancy.cielo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils

/**
 * The family's palette, shared with Fold Motion, Fold Taskbar and Fold Widgets: smoked
 * green-black, ivory ink, champagne for what is chosen. One colour is new, and a need put it
 * here: rain has to read apart from temperature at a glance, so water is a cool acqua and
 * nothing else in the app is blue.
 */
object Palette {
    const val GROUND = 0xFF0E1311.toInt()
    const val SURFACE = 0xFF151C18.toInt()
    const val RAISED = 0xFF1D2621.toInt()
    const val HAIRLINE = 0x1FF6F1E6
    const val IVORY = 0xFFF6F1E6.toInt()
    const val MUTED = 0xFF96A098.toInt()
    const val FAINT = 0xFF5E6862.toInt()
    const val CHAMPAGNE = 0xFFD9BB86.toInt()
    const val ACQUA = 0xFF8FB3C9.toInt()
    const val ROSSO = 0xFFD7262E.toInt()
}

object Type {
    private var display: Typeface? = null
    private val jost = HashMap<Int, Typeface>()

    fun display(c: Context): Typeface = display ?: runCatching {
        Typeface.createFromAsset(c.assets, "fonts/Italiana-Regular.ttf")
    }.getOrDefault(Typeface.SERIF).also { display = it }

    /** Jost at a given weight (variable font), falling back to the system sans. */
    fun sans(c: Context, weight: Int = 400): Typeface = synchronized(jost) {
        jost.getOrPut(weight) {
            runCatching {
                Typeface.Builder(c.assets, "fonts/Jost.ttf").setFontVariationSettings("'wght' $weight").build()
            }.getOrNull() ?: Typeface.create(Typeface.SANS_SERIF, weight, false)
        }
    }
}

/**
 * The family's type, one system (Fold Widgets, 1 Nov). Italiana only for the one large figure
 * the screen is about, here the temperature. Jost for every word.
 */
class Ink(private val c: Context, val dp: Float) {
    private fun p(size: Float, colour: Int, weight: Int, display: Boolean = false, tracking: Float = 0f) =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = if (display) Type.display(c) else Type.sans(c, weight)
            textSize = size * dp
            color = colour
            letterSpacing = tracking
        }

    val figure = p(150f, Palette.IVORY, 400, display = true)
    val figureSmall = p(30f, Palette.IVORY, 400, display = true)
    val heading = p(15f, Palette.IVORY, 600)
    val statement = p(19f, Palette.IVORY, 500)
    val primary = p(14.5f, Palette.IVORY, 600)
    val body = p(14f, Palette.IVORY, 450)
    val meta = p(12.5f, Palette.MUTED, 500)
    val label = p(11f, Palette.MUTED, 600, tracking = 0.14f)
    val figures = p(14.5f, Palette.IVORY, 500).apply { fontFeatureSettings = "tnum" }
    val tile = p(24f, Palette.IVORY, 500)
}

/** [s] cut to [width] with an ellipsis. */
fun fit(s: CharSequence, p: TextPaint, width: Float): String =
    TextUtils.ellipsize(s, p, width.coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()

/** [s] broken into at most [lines] lines of [width], only between words. */
fun wrap(s: String, p: TextPaint, width: Float, lines: Int): List<String> {
    val words = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return listOf("")
    val out = ArrayList<String>()
    var i = 0
    while (i < words.size && out.size < lines) {
        var line = words[i]
        if (p.measureText(line) > width) { out += fit(line, p, width); i++; continue }
        i++
        while (i < words.size && p.measureText("$line ${words[i]}") <= width) { line += " " + words[i]; i++ }
        out += line
    }
    if (i < words.size && out.isNotEmpty()) {
        val last = out.removeAt(out.size - 1)
        out += fit("$last…", p, width)
    }
    return out
}

/** Draws [s] with its cap height's top at [top], so blocks align by eye rather than by baseline. */
fun Canvas.textTop(s: String, x: Float, top: Float, p: Paint) = drawText(s, x, top + capHeight(p), p)

private val capCache = HashMap<Pair<Typeface?, Float>, Float>()

fun capHeight(p: Paint): Float = capCache.getOrPut(p.typeface to p.textSize) {
    val r = Rect(); p.getTextBounds("H", 0, 1, r); -r.top.toFloat()
}

/** [colour] at [alpha] (0 to 1) of its own alpha. */
fun alpha(colour: Int, a: Float): Int {
    val base = (colour ushr 24) and 0xFF
    val na = (base * a.coerceIn(0f, 1f)).toInt()
    return (colour and 0x00FFFFFF) or (na shl 24)
}

/** Linear blend of two colours. */
fun mix(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun ch(s: Int) = (((a shr s) and 0xFF) + ((((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * k)).toInt() shl s
    return ch(24) or ch(16) or ch(8) or ch(0)
}
