package com.fergusonconsultancy.cielo

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The screen's one light, caused by the sky it describes. Its position is the sun's progress
 * from sunrise (left, east) to sunset (right, west); its strength is the sun's height; cloud
 * dims and spreads it; rain cools it. At night it is a faint warm ember. Nothing about it is
 * chosen for looks: every property is a reading.
 */
class Light(val x: Float, val strength: Float, val reach: Float, val colour: Int) {

    companion object {
        /** The sun's elevation in degrees for a place at a moment (NOAA's low-precision formulae). */
        fun elevation(lat: Double, lon: Double, utcMillis: Long): Double {
            val d = utcMillis / 86_400_000.0 + 2440587.5 - 2451545.0
            val g = Math.toRadians((357.529 + 0.98560028 * d) % 360)
            val q = (280.459 + 0.98564736 * d) % 360
            val l = Math.toRadians((q + 1.915 * sin(g) + 0.020 * sin(2 * g)) % 360)
            val e = Math.toRadians(23.439 - 0.00000036 * d)
            val ra = atan2(cos(e) * sin(l), cos(l))
            val dec = asin(sin(e) * sin(l))
            val gmst = ((18.697374558 + 24.06570982441908 * d) % 24 + 24) % 24
            val ha = Math.toRadians(gmst * 15 + lon) - ra
            val la = Math.toRadians(lat)
            return Math.toDegrees(asin(sin(la) * sin(dec) + cos(la) * cos(dec) * cos(ha)))
        }

        private fun smooth(a: Double, b: Double, x: Double): Float {
            val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0); return (t * t * (3 - 2 * t)).toFloat()
        }

        fun of(p: Place, f: Forecast?, now: Long = System.currentTimeMillis()): Light {
            val elev = elevation(p.lat, p.lon, now)
            val day = smooth(-6.0, 8.0, elev)
            val high = smooth(5.0, 45.0, elev)
            val cloud = (f?.cloud ?: 40) / 100f
            val wet = f != null && Weather.wet(f.code)
            val progress = (f?.daylight() ?: 0.5f)
            // Night: the ember sits where the sun went down or will come up, whichever is nearer
            val x = if (day > 0.05f) progress.coerceIn(0.04f, 0.96f) else if (progress > 1f) 0.92f else 0.08f
            val strength = (0.07f + day * (0.13f + 0.16f * high)) * (1f - 0.5f * cloud * day)
            val reach = 0.55f + 0.35f * high + 0.25f * cloud * day
            // Low sun warm amber, high sun pale champagne, night a dim ember; rain cools all of them
            var colour = mix(0xFFE39A52.toInt(), 0xFFF0E2C2.toInt(), high)
            colour = mix(0xFFB98A5A.toInt(), colour, day)
            if (wet) colour = mix(colour, Palette.ACQUA, 0.35f)
            return Light(x, strength, reach, colour)
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    /** Lights a [w] × [h] area from its top edge, at [k] of its strength. */
    fun draw(c: Canvas, left: Float, w: Float, h: Float, k: Float) {
        if (k <= 0.001f || w <= 0f || h <= 0f) return
        val cx = left + w * x
        val r = maxOf(w, h * 0.7f) * reach
        paint.shader = RadialGradient(cx, -r * 0.18f, r,
            intArrayOf(alpha(colour, strength * k), alpha(colour, strength * k * 0.42f), alpha(colour, strength * k * 0.12f), 0),
            floatArrayOf(0f, 0.32f, 0.66f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(left, 0f, left + w, minOf(h, r * 1.1f), paint)
    }
}

/** Sky glyphs, made only of circles and fine lines: the circle is the family's perfect shape. */
object Glyphs {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cloudCache = HashMap<Long, Path>()

    private fun cloud(cx: Float, cy: Float, s: Float): Path {
        val key = (cx.toInt().toLong() shl 40) or (cy.toInt().toLong() shl 20) or s.toInt().toLong()
        return cloudCache.getOrPut(key) {
            val p = Path()
            p.addCircle(cx - 0.17f * s, cy + 0.04f * s, 0.19f * s, Path.Direction.CW)
            val q = Path(); q.addCircle(cx + 0.04f * s, cy - 0.07f * s, 0.25f * s, Path.Direction.CW)
            val r = Path(); r.addCircle(cx + 0.25f * s, cy + 0.07f * s, 0.16f * s, Path.Direction.CW)
            val b = Path(); b.addRoundRect(cx - 0.36f * s, cy + 0.02f * s, cx + 0.41f * s, cy + 0.23f * s, 0.105f * s, 0.105f * s, Path.Direction.CW)
            p.op(q, Path.Op.UNION); p.op(r, Path.Op.UNION); p.op(b, Path.Op.UNION)
            if (cloudCache.size > 64) cloudCache.clear()
            p
        }
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float, a: Float) {
        fill.shader = null
        fill.color = alpha(Palette.CHAMPAGNE, a)
        c.drawCircle(cx, cy, r, fill)
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, r: Float, a: Float) {
        val p = Path(); p.addCircle(cx, cy, r, Path.Direction.CW)
        val bite = Path(); bite.addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.85f, Path.Direction.CW)
        p.op(bite, Path.Op.DIFFERENCE)
        fill.shader = null; fill.color = alpha(Palette.IVORY, 0.9f * a)
        c.drawPath(p, fill)
    }

    /** Draws [sky] centred in a box of side [s]. */
    fun draw(c: Canvas, sky: Weather.Sky, day: Boolean, cx: Float, cy: Float, s: Float, dp: Float, a: Float = 1f) {
        stroke.strokeWidth = maxOf(1.2f * dp, s * 0.045f)
        stroke.color = alpha(Palette.IVORY, 0.92f * a)
        fun body(x: Float, y: Float) = if (day) sun(c, x, y, s * 0.2f, a) else moon(c, x, y, s * 0.19f, a)
        fun drops(n: Int, colour: Int, ring: Boolean) {
            val y0 = cy + 0.38f * s
            val gap = 0.17f * s
            val x0 = cx + 0.02f * s - gap * (n - 1) / 2f
            for (i in 0 until n) {
                val x = x0 + i * gap
                val y = y0 + (if (i % 2 == 1) 0.05f * s else 0f)
                if (ring) { stroke.color = alpha(colour, a); c.drawCircle(x, y, 0.045f * s, stroke); stroke.color = alpha(Palette.IVORY, 0.92f * a) }
                else { fill.shader = null; fill.color = alpha(colour, a); c.drawCircle(x, y, 0.05f * s, fill) }
            }
        }
        when (sky) {
            Weather.Sky.CLEAR -> body(cx, cy)
            Weather.Sky.PARTLY -> {
                body(cx - 0.12f * s, cy - 0.16f * s)
                // The cloud passes in front: clear its shape before drawing its line
                fill.shader = null; fill.color = Palette.GROUND
                val p = cloud(cx + 0.06f * s, cy + 0.08f * s, s * 0.82f)
                c.drawPath(p, fill); c.drawPath(p, stroke)
            }
            Weather.Sky.CLOUD -> c.drawPath(cloud(cx, cy, s), stroke)
            Weather.Sky.FOG -> {
                for (i in 0..2) {
                    val w = s * (0.7f - i * 0.14f)
                    stroke.color = alpha(Palette.IVORY, (0.9f - i * 0.25f) * a)
                    c.drawLine(cx - w / 2, cy - 0.16f * s + i * 0.16f * s, cx + w / 2, cy - 0.16f * s + i * 0.16f * s, stroke)
                }
            }
            Weather.Sky.DRIZZLE -> { c.drawPath(cloud(cx, cy - 0.1f * s, s), stroke); drops(2, Palette.ACQUA, false) }
            Weather.Sky.RAIN -> { c.drawPath(cloud(cx, cy - 0.1f * s, s), stroke); drops(3, Palette.ACQUA, false) }
            Weather.Sky.SNOW -> { c.drawPath(cloud(cx, cy - 0.1f * s, s), stroke); drops(3, Palette.IVORY, true) }
            Weather.Sky.STORM -> {
                c.drawPath(cloud(cx, cy - 0.1f * s, s), stroke)
                stroke.color = alpha(Palette.CHAMPAGNE, a)
                val p = Path()
                p.moveTo(cx + 0.06f * s, cy + 0.22f * s); p.lineTo(cx - 0.04f * s, cy + 0.36f * s)
                p.lineTo(cx + 0.05f * s, cy + 0.36f * s); p.lineTo(cx - 0.05f * s, cy + 0.5f * s)
                c.drawPath(p, stroke)
            }
        }
    }

    /** A fine ring with one needle, after Alfa's instrument binnacles. [deg] is where it points. */
    fun needle(c: Canvas, cx: Float, cy: Float, r: Float, deg: Float, dp: Float, a: Float) {
        stroke.strokeWidth = 1f * dp; stroke.color = alpha(Palette.IVORY, 0.28f * a)
        c.drawCircle(cx, cy, r, stroke)
        // North tick
        c.drawLine(cx, cy - r, cx, cy - r + 4 * dp, stroke)
        val rad = Math.toRadians(deg.toDouble() - 90)
        stroke.strokeWidth = 1.6f * dp; stroke.color = alpha(Palette.CHAMPAGNE, a)
        val ex = cx + (r - 3 * dp) * cos(rad).toFloat(); val ey = cy + (r - 3 * dp) * sin(rad).toFloat()
        val bx = cx - (r * 0.45f) * cos(rad).toFloat(); val by = cy - (r * 0.45f) * sin(rad).toFloat()
        c.drawLine(bx, by, ex, ey, stroke)
        fill.shader = null; fill.color = alpha(Palette.CHAMPAGNE, a); c.drawCircle(cx, cy, 2.2f * dp, fill)
    }

    /** The day's arc from sunrise to sunset with the sun on it at [t] (0..1); below the line at night. */
    fun sunArc(c: Canvas, cx: Float, base: Float, r: Float, t: Float, dp: Float, a: Float) {
        stroke.strokeWidth = 1f * dp; stroke.color = alpha(Palette.IVORY, 0.28f * a)
        c.drawArc(cx - r, base - r, cx + r, base + r, 180f, 180f, false, stroke)
        c.drawLine(cx - r - 4 * dp, base, cx + r + 4 * dp, base, stroke)
        val k = t.coerceIn(0f, 1f)
        val ang = PI * (1 - k)
        val x = cx + r * cos(ang).toFloat(); val y = base - r * sin(ang).toFloat()
        val up = t in 0f..1f
        fill.shader = null; fill.color = alpha(if (up) Palette.CHAMPAGNE else Palette.FAINT, a)
        c.drawCircle(x, if (up) y else base, 4f * dp, fill)
    }

    /** A ring filled to [share] (0..1) in [colour], as the battery's Anello. */
    fun ring(c: Canvas, cx: Float, cy: Float, r: Float, share: Float, colour: Int, dp: Float, a: Float) {
        stroke.strokeWidth = 1f * dp; stroke.color = alpha(Palette.IVORY, 0.2f * a)
        c.drawCircle(cx, cy, r, stroke)
        stroke.strokeWidth = 2f * dp; stroke.color = alpha(colour, a)
        c.drawArc(cx - r, cy - r, cx + r, cy + r, -90f, 360f * share.coerceIn(0f, 1f), false, stroke)
    }
}
