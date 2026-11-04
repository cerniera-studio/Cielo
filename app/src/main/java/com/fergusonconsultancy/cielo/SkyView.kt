package com.fergusonconsultancy.cielo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The whole app is one canvas, one membrane: the sky for each place, laid out for the cover screen
 * (one column) or the main screen (two columns meeting at the crease). Every motion on it is a
 * reading of a force: the finger, the fold, or the sky itself.
 */
@SuppressLint("ViewConstructor")
class SkyView(context: Context) : View(context) {

    // ---- what it shows ----
    var places: List<Place> = emptyList()
    var forecastOf: (Place) -> Forecast? = { null }
    var units = Units(false, "mph")
    var hapticsOn = true
    var concentratoOn = true
    var busy: Set<String> = emptySet()
    var failed: Set<String> = emptySet()
    var page = 0
        private set

    var onPlaces: () -> Unit = {}
    var onSettings: () -> Unit = {}
    var onRefresh: (Place) -> Unit = {}
    var onPage: (Int) -> Unit = {}

    var topInset = 0f
    var bottomInset = 0f

    private val dp = resources.displayMetrics.density
    private val ink = Ink(context, dp)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    // ---- layout ----
    private enum class Tile { WIND, SUN, FEELS, RAIN, UV, AIR, VIS }

    private class Lay(
        val wide: Boolean, val crease: Float,
        val aL: Float, val aR: Float, val bL: Float, val bR: Float,
        val header: RectF, val hero: RectF, val verdict: RectF, val verdictLines: List<String>,
        val curve: RectF, val plot: RectF, val week: RectF, val weekLines: List<String>, val rows: List<RectF>,
        val tiles: List<Pair<Tile, RectF>>, val foot: RectF, val placesBtn: RectF, val settingsBtn: RectF,
        val height: Float,
    )

    private val lays = HashMap<String, Lay>()

    private fun key(p: Place, f: Forecast?) = "${p.id}|${f?.fetched}|$width|$height|${units.fahrenheit}|$topInset|$bottomInset|${p.id in failed}"

    private fun layFor(i: Int): Lay {
        val p = places[i]; val f = forecastOf(p)
        return lays.getOrPut(key(p, f)) { layout(f, p.id in failed).also { if (i == page) record(it) } }.also { if (lays.size > 24) lays.clear() }
    }

    private val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Debug builds only: where things are, in screen pixels at rest, for the launch test to touch. */
    private fun record(L: Lay) {
        if (!debuggable) return
        fun r(x: RectF) = "[${x.left.toInt()},${x.top.toInt()},${x.right.toInt()},${x.bottom.toInt()}]"
        val json = "{\"plot\":${r(L.plot)},\"header\":${r(L.header)},\"rows\":[${L.rows.joinToString(",") { r(it) }}]," +
            "\"settings\":${r(L.settingsBtn)},\"places\":${r(L.placesBtn)},\"height\":${L.height.toInt()},\"wide\":${L.wide}}"
        runCatching { java.io.File(context.filesDir, "layout.json").writeText(json) }
    }

    private fun tilesFor(f: Forecast?): List<Tile> {
        if (f == null) return emptyList()
        val t = mutableListOf(Tile.WIND, Tile.SUN, Tile.FEELS)
        // Giulietta: a tile shows only when it answers something today
        val rain = f.dPrecip.getOrNull(0)?.takeIf { !it.isNaN() } ?: 0.0
        if (rain >= 0.2 || (f.dRain.getOrNull(0) ?: 0) >= 30) t += Tile.RAIN
        if ((f.dUv.getOrNull(0)?.takeIf { !it.isNaN() } ?: 0.0) >= 3.0) t += Tile.UV
        if ((f.aqi ?: 0) > 40) t += Tile.AIR
        if ((f.visibility ?: 99_999.0) < 5_000.0) t += Tile.VIS
        return t
    }

    private fun layout(f: Forecast?, failedNow: Boolean): Lay {
        val w = width.toFloat()
        val wide = w >= 600 * dp
        val pad = if (wide) 28 * dp else 22 * dp
        val crease = w / 2
        val aL = pad; val aR = if (wide) crease - pad * 0.8f else w - pad
        val bL = if (wide) crease + pad * 0.8f else aL; val bR = w - pad
        val aW = aR - aL; val bW = bR - bL

        var y = topInset + 10 * dp
        val header = RectF(aL, y, aR, y + 44 * dp); y = header.bottom + 6 * dp
        val heroH = capHeight(ink.figure) + 26 * dp
        val hero = RectF(aL, y, aR, y + heroH); y = hero.bottom + 4 * dp
        val vLines = if (f == null) wrap(statusLine(null, failedNow), ink.statement, aW, 3) else wrap(Weather.verdict(f), ink.statement, aW, 2)
        val vH = vLines.size * ink.statement.textSize * 1.22f + 10 * dp + ink.meta.textSize * 1.3f
        val verdict = RectF(aL, y, aR, y + vH); y = verdict.bottom + 26 * dp

        val plotH = if (wide) (height - bottomInset - y - 30 * dp - 48 * dp - 24 * dp).coerceIn(190 * dp, 300 * dp) else 140 * dp
        val curveTop = y
        val plot = RectF(aL, curveTop + 30 * dp, aR, curveTop + 30 * dp + plotH)
        val curve = RectF(aL, curveTop, aR, plot.bottom + 48 * dp)
        val aBottom = curve.bottom

        var yb = if (wide) topInset + 18 * dp else curve.bottom + 30 * dp
        val weekLines = if (f == null) emptyList() else wrap(Weather.weekLine(f), ink.body, bW, 2)
        val week = RectF(bL, yb, bR, yb + 22 * dp + weekLines.size * ink.body.textSize * 1.3f + 8 * dp)
        yb = week.bottom
        val n = if (f == null) 0 else min(7, f.days)
        val rowH = 52 * dp
        val rows = (0 until n).map { RectF(bL, yb + it * rowH, bR, yb + (it + 1) * rowH) }
        yb += n * rowH + if (n > 0) 26 * dp else 0f

        val kinds = tilesFor(f)
        val gap = 10 * dp
        val across = if (wide) 3 else 2
        val tileW = (bW - gap * (across - 1)) / across
        val tileH = 118 * dp
        val tiles = ArrayList<Pair<Tile, RectF>>()
        if (kinds.isNotEmpty()) yb += 22 * dp  // the "Now" label
        kinds.forEachIndexed { i, k ->
            val row = i / across; val colI = i % across
            val last = !wide && i == kinds.size - 1 && kinds.size % 2 == 1
            val l = if (last) bL else bL + colI * (tileW + gap)
            val r = if (last) bR else l + tileW
            tiles += k to RectF(l, yb + row * (tileH + gap), r, yb + row * (tileH + gap) + tileH)
        }
        if (kinds.isNotEmpty()) yb = tiles.last().second.bottom + 30 * dp

        val foot = RectF(bL, yb, bR, yb + 96 * dp)
        val placesBtn = RectF(bL - 8 * dp, foot.top, bL + 90 * dp, foot.top + 44 * dp)
        val settingsBtn = RectF(placesBtn.right, foot.top, placesBtn.right + 110 * dp, foot.top + 44 * dp)
        val h = max(aBottom + 24 * dp, foot.bottom) + bottomInset
        return Lay(wide, crease, aL, aR, bL, bR, header, hero, verdict, vLines, curve, plot, week, weekLines, rows, tiles, foot, placesBtn, settingsBtn, h)
    }

    // ---- motion state ----
    private var scroll = 0f
    private val scroller = OverScroller(context)
    private val pull = Spring(0f, stiffness = 220f, damping = 19f)      // soft: the membrane may swell past and settle
    private var pullRaw = 0f
    private var armed = false
    private val pageX = Spring(0f, stiffness = 300f, damping = 34.6f)    // rigid: critically damped, no bounce
    private var dotsUntil = 0L
    private val bump = Spring(0f, stiffness = 320f, damping = 20f, eps = 0.002f)
    private var scrubX = -1f
    private var scrubHour = -1
    private val calm = Spring(1f, stiffness = 180f, damping = 26f, eps = 0.002f)  // Concentrato: 1 is full ornament
    private var day = 0
    private var morph: Tween? = null
    private var fromFrac: FloatArray? = null
    private var pressed = -1
    private val press = Spring(0f, stiffness = 420f, damping = 22f, eps = 0.002f)

    // The fold's entrance: where content comes from, and the one climax at its end
    private var entrance: Tween? = null
    private var fromCrease = false
    private var ornament = 1f
    private val sun = Spring(1f, stiffness = 300f, damping = 13f, eps = 0.002f)
    private var sunKicked = true
    private var pendingFold: Pair<Boolean, Float>? = null
    private var lastW = 0

    private var last = 0L

    fun setPage(i: Int, animate: Boolean = false) {
        val target = i.coerceIn(0, max(0, places.size - 1))
        if (animate && target != page && width > 0) pageX.snap(pageX.x + (target - page) * width.toFloat())
        page = target
        pageX.to(0f)
        day = 0; morph = null; fromFrac = null
        scroll = scroll.coerceAtMost(maxScroll())
        dotsUntil = SystemClock.uptimeMillis() + 1600
        describe(); invalidate()
    }

    /** Data changed: drop cached layouts, keep the reading position. */
    fun refreshData() {
        lays.clear()
        page = page.coerceIn(0, max(0, places.size - 1))
        scroll = scroll.coerceIn(0f, maxScroll())
        describe(); invalidate()
    }

    /**
     * The phone folded or unfolded. [speed] is 0 for a slow fold, 1 for a fast one. Content comes
     * from the hinge: out of the crease when opening, in from the cover's left edge when closing.
     */
    fun folded(open: Boolean, speed: Float) {
        pendingFold = open to (if (concentratoOn) speed else 0f)
        // The new size usually arrives just after; if it doesn't (the same width), start anyway
        postDelayed({ startFold() }, 160)
    }

    private fun startFold() {
        val (open, speed) = pendingFold ?: return
        pendingFold = null
        fromCrease = open
        ornament = 1f - 0.75f * speed
        entrance = Tween(440f * (1f - 0.35f * speed))
        sunKicked = false; sun.snap(0f)
        scroll = 0f; scroller.forceFinished(true)
        invalidate()
    }

    /** First data on screen: a short rise, with the light already there on the first frame. */
    fun arrive() {
        fromCrease = false; ornament = 1f
        entrance = Tween(420f); sunKicked = false; sun.snap(0f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        lays.clear()
        scroll = scroll.coerceIn(0f, maxScroll())
        if (pendingFold != null) startFold()
        lastW = w
    }

    private fun maxScroll(): Float = if (places.isEmpty() || height == 0) 0f else max(0f, layFor(page).height - height)

    // ---- drawing ----
    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1000f).coerceIn(0.001f, 0.05f)
        last = now
        var moving = false
        if (scroller.computeScrollOffset()) { scroll = scroller.currY.toFloat(); moving = true; feelSpeed(abs(scroller.currVelocity) / 1000f) }
        moving = pull.step(dt) or moving
        moving = pageX.step(dt) or moving
        moving = bump.step(dt) or moving
        moving = press.step(dt) or moving
        moving = sun.step(dt) or moving
        if (scroller.isFinished && mode == Mode.NONE) calm.to(1f)
        moving = calm.step(dt) or moving
        entrance?.let { if (it.over(900f)) entrance = null else moving = true }
        morph?.let { if (it.done) { morph = null; fromFrac = null } else moving = true }
        if (now < dotsUntil) moving = true

        c.drawColor(Palette.GROUND)
        if (places.isEmpty()) return
        val w = width.toFloat()
        val prog = (-pageX.x / w)  // where the swipe sits between this page (0) and the next (±1)
        val neighbour = page + if (prog > 0) 1 else -1

        // The light: each place's own sky, crossfading as you travel between them
        val lA = Light.of(places[page], forecastOf(places[page]))
        val wake = entrance?.let { 0.6f + 0.4f * it.at(0f, 700f) + 0.25f * ornament * (1f - it.at(260f, 640f)) * it.at(0f, 260f) } ?: 1f
        lA.draw(c, 0f, w, height.toFloat(), (1f - abs(prog).coerceAtMost(1f)) * wake)
        if (neighbour in places.indices && abs(prog) > 0.001f)
            Light.of(places[neighbour], forecastOf(places[neighbour])).draw(c, 0f, w, height.toFloat(), abs(prog).coerceAtMost(1f))

        drawPage(c, page, pageX.x, prog)
        if (neighbour in places.indices && abs(prog) > 0.001f)
            drawPage(c, neighbour, pageX.x + (neighbour - page) * w, prog)

        drawPull(c)
        if (moving) postInvalidateOnAnimation()
    }

    /** Lower pieces lag mid-swipe, so the page stretches like a membrane and gathers as it lands. */
    private fun lag(depth: Float, prog: Float): Float {
        val p = abs(prog).coerceAtMost(1f)
        return -Math.signum(prog) * width * 0.16f * depth * 4f * p * (1f - p)
    }

    private fun drawPage(c: Canvas, i: Int, baseX: Float, prog: Float) {
        val p = places[i]
        val f = forecastOf(p)
        val L = layFor(i)
        val current = i == page
        val k = calm.x
        val ent = entrance

        // Entrance offset and opacity for the piece at [order] in column [col] (0 left, 1 right)
        fun enter(order: Int, col: Int): Pair<Float, Float> {
            val e = ent ?: return 0f to 1f
            val t = e.at(order * 38f * ornament, 440f)
            val d = 30 * dp * (1f - t)
            val dx = when {
                !fromCrease -> 0f
                L.wide && col == 0 -> d
                else -> -d
            }
            return dx to t
        }

        fun yOf(top: Float) = -scroll + pull.x * (1f - 0.5f * (top / L.height).coerceIn(0f, 1f))

        // ---- header ----
        run {
            val (ex, ea) = enter(0, 0)
            val save = c.save()
            c.translate(baseX + ex + lag(0f, prog), yOf(L.header.top))
            val r = L.header
            ink.heading.color = alpha(Palette.IVORY, ea)
            val name = fit(p.name, ink.heading, (r.width() * 0.6f))
            val cy = r.centerY()
            c.drawText(name, r.left, cy + capHeight(ink.heading) / 2, ink.heading)
            val nw = ink.heading.measureText(name)
            // A fine chevron: the name opens the places
            line.strokeWidth = 1.4f * dp; line.color = alpha(Palette.MUTED, ea)
            val chx = r.left + nw + 9 * dp
            c.drawLine(chx, cy - 2 * dp, chx + 4 * dp, cy + 2 * dp, line)
            c.drawLine(chx + 4 * dp, cy + 2 * dp, chx + 8 * dp, cy - 2 * dp, line)
            // Where you are among the places, only while travelling (Giulietta)
            val dots = if (places.size > 1) {
                val t = SystemClock.uptimeMillis()
                if (abs(pageX.x) > 1f || t < dotsUntil) 1f else 0f
            } else 0f
            if (dots > 0f && current) {
                for (j in places.indices) {
                    fill.color = alpha(if (j == page) Palette.CHAMPAGNE else Palette.FAINT, ea)
                    c.drawCircle(chx + 22 * dp + j * 9 * dp, cy, 2.4f * dp, fill)
                }
            }
            val stamp = statusStamp(p, f)
            ink.meta.color = alpha(Palette.MUTED, ea * (0.25f + 0.75f * k))
            c.drawText(stamp, r.right - ink.meta.measureText(stamp), cy + capHeight(ink.meta) / 2, ink.meta)
            c.restoreToCount(save)
        }

        if (f == null) {
            val (ex, ea) = enter(1, 0)
            val save = c.save()
            c.translate(baseX + ex + lag(0.3f, prog), yOf(L.verdict.top))
            ink.statement.color = alpha(Palette.IVORY, 0.85f * ea)
            L.verdictLines.forEachIndexed { n, s -> c.drawText(s, L.aL, L.verdict.top + capHeight(ink.statement) + n * ink.statement.textSize * 1.22f, ink.statement) }
            c.restoreToCount(save)
            return
        }

        val scrubbing = current && scrubHour >= 0
        val hourIdx = if (scrubbing) day * 24 + scrubHour else f.now
        val atNow = !scrubbing && day == 0

        // ---- hero: the one large figure ----
        run {
            val (ex, ea) = enter(1, 0)
            val save = c.save()
            c.translate(baseX + ex + lag(0.25f, prog), yOf(L.hero.top))
            val r = L.hero
            val tempC = if (atNow) f.temp else if (scrubbing) f.hTemp.getOrElse(hourIdx) { f.temp } else f.dMax.getOrElse(day) { f.temp }
            val s = units.t(tempC).removeSuffix("°")
            val fig = ink.figure
            fig.color = alpha(Palette.IVORY, ea)
            var size = 150 * dp
            fig.textSize = size
            val room = r.width() - 110 * dp
            while (fig.measureText(s) > room && size > 70 * dp) { size -= 6 * dp; fig.textSize = size }
            val ch = capHeight(fig)
            c.drawText(s, r.left - 4 * dp, r.top + ch, fig)
            // The degree: a fine ring at cap height, in the figure's own stroke weight. Italiana's
            // own degree sign is drawn at display size and floats loose of the numerals
            val ring = size * 0.075f
            line.strokeWidth = size * 0.018f; line.color = alpha(Palette.IVORY, ea)
            c.drawCircle(r.left - 4 * dp + fig.measureText(s) + size * 0.05f + ring, r.top + ring + line.strokeWidth, ring, line)
            fig.textSize = 150 * dp
            val code = if (atNow) f.code else if (scrubbing) f.hCode.getOrElse(hourIdx) { f.code } else f.dCode.getOrElse(day) { f.code }
            val isDay = if (atNow) f.isDay else if (scrubbing) f.hDay.getOrElse(hourIdx) { true } else true
            val g = 60 * dp
            Glyphs.draw(c, Weather.describe(code).second, isDay, r.right - g / 2, r.top + g * 0.5f, g, dp, ea)
            // The day's range under the glyph
            val hi = units.t(f.dMax.getOrElse(day) { Double.NaN }); val lo = units.t(f.dMin.getOrElse(day) { Double.NaN })
            ink.figures.color = alpha(Palette.IVORY, ea); ink.meta.color = alpha(Palette.MUTED, ea)
            val yRange = r.top + g + 22 * dp
            val loW = ink.meta.measureText(lo)
            c.drawText(lo, r.right - loW, yRange, ink.meta)
            c.drawText(hi, r.right - loW - 8 * dp - ink.figures.measureText(hi), yRange, ink.figures)
            c.restoreToCount(save)
        }

        // ---- verdict: the glance ----
        run {
            val (ex, ea) = enter(2, 0)
            val save = c.save()
            c.translate(baseX + ex + lag(0.4f, prog), yOf(L.verdict.top))
            val r = L.verdict
            val lines: List<String>
            val sub: String
            if (scrubbing) {
                val words = Weather.describe(f.hCode.getOrElse(hourIdx) { f.code }, f.hDay.getOrElse(hourIdx) { true }).first
                val rain = f.hRain.getOrElse(hourIdx) { 0 }
                lines = wrap(if (rain >= 20) "$words, $rain% chance of rain" else words, ink.statement, r.width(), 2)
                sub = "At ${f.hourOf(hourIdx)} " + (if (day == 0) "today" else "on " + Weather.dayName(f.dDate[day], day, short = false))
            } else if (day > 0) {
                val words = Weather.describe(f.dCode.getOrElse(day) { 3 }).first
                val rain = f.dRain.getOrElse(day) { 0 }
                lines = wrap(if (rain >= 20) "$words, $rain% chance of rain" else words, ink.statement, r.width(), 2)
                sub = Weather.dayName(f.dDate[day], day, short = false) + " · high and low"
            } else {
                lines = L.verdictLines
                val words = Weather.describe(f.code, f.isDay).first
                sub = words + (f.feels?.let { " · feels ${units.t(it)}" } ?: "")
            }
            ink.statement.color = alpha(Palette.IVORY, ea)
            var y = r.top + capHeight(ink.statement)
            lines.forEach { s -> c.drawText(s, r.left, y, ink.statement); y += ink.statement.textSize * 1.22f }
            ink.meta.color = alpha(Palette.MUTED, ea)
            c.drawText(sub, r.left, y - ink.statement.textSize * 1.22f + ink.meta.textSize * 1.3f + 10 * dp, ink.meta)
            c.restoreToCount(save)
        }

        // ---- the day curve ----
        run {
            val (ex, ea) = enter(3, 0)
            val save = c.save()
            c.translate(baseX + ex + lag(0.6f, prog), yOf(L.curve.top))
            drawCurve(c, L, f, current, ea, k)
            c.restoreToCount(save)
        }

        // ---- the week ----
        run {
            val (ex, ea) = enter(if (L.wide) 0 else 4, 1)
            val save = c.save()
            c.translate(baseX + ex + lag(if (L.wide) 0.1f else 0.75f, prog), yOf(L.week.top))
            ink.label.color = alpha(Palette.MUTED, ea * (0.25f + 0.75f * k))
            c.drawText("THE WEEK", L.week.left, L.week.top + capHeight(ink.label), ink.label)
            ink.body.color = alpha(Palette.IVORY, 0.78f * ea)
            var y = L.week.top + 22 * dp + capHeight(ink.body)
            L.weekLines.forEach { s -> c.drawText(s, L.week.left, y, ink.body); y += ink.body.textSize * 1.3f }
            c.restoreToCount(save)
        }
        val wMin = (0 until L.rows.size).minOfOrNull { f.dMin.getOrElse(it) { 0.0 } } ?: 0.0
        val wMax = (0 until L.rows.size).maxOfOrNull { f.dMax.getOrElse(it) { 1.0 } } ?: 1.0
        L.rows.forEachIndexed { n, r ->
            val (ex, ea) = enter((if (L.wide) 1 else 5) + n, 1)
            val save = c.save()
            c.translate(baseX + ex + lag(if (L.wide) 0.15f + n * 0.05f else 0.8f + n * 0.03f, prog), yOf(r.top))
            if (current && n == pressed && press.x > 0.001f) {
                val s = 1f - 0.018f * press.x
                c.scale(s, s, r.centerX(), r.centerY())
            }
            drawRow(c, r, f, n, n == day, wMin, wMax, ea, k)
            c.restoreToCount(save)
        }

        // ---- now: the tiles ----
        if (L.tiles.isNotEmpty()) {
            val base = (if (L.wide) 2 else 6) + L.rows.size / 2
            val (ex0, ea0) = enter(base, 1)
            val first = L.tiles.first().second
            val save0 = c.save()
            c.translate(baseX + ex0 + lag(0.85f, prog), yOf(first.top))
            ink.label.color = alpha(Palette.MUTED, ea0 * (0.25f + 0.75f * k))
            c.drawText("NOW", L.bL, first.top - 22 * dp + capHeight(ink.label), ink.label)
            c.restoreToCount(save0)
            L.tiles.forEachIndexed { n, (kind, r) ->
                val (ex, ea) = enter(base + 1 + n / 2, 1)
                val save = c.save()
                c.translate(baseX + ex + lag(0.9f, prog), yOf(r.top))
                drawTile(c, kind, r, f, ea, k)
                c.restoreToCount(save)
            }
        }

        // ---- foot ----
        run {
            val (ex, ea) = enter(12, 1)
            val save = c.save()
            c.translate(baseX + ex + lag(1f, prog), yOf(L.foot.top))
            ink.primary.color = alpha(Palette.CHAMPAGNE, ea)
            val y = L.placesBtn.centerY() + capHeight(ink.primary) / 2
            c.drawText("Places", L.placesBtn.left + 8 * dp, y, ink.primary)
            c.drawText("Settings", L.settingsBtn.left + 8 * dp, y, ink.primary)
            ink.meta.color = alpha(Palette.FAINT, ea)
            c.drawText("Weather data by Open-Meteo.com", L.foot.left, L.foot.top + 66 * dp, ink.meta)
            c.restoreToCount(save)
        }
        ink.primary.color = Palette.IVORY
    }

    private val curvePath = Path()
    private val pastPath = Path()
    private val measure = PathMeasure()

    /** The day's temperature as y fractions (0 low, 1 high) over a range of at least 6°. */
    private fun fractions(f: Forecast, d: Int): FloatArray {
        val t = f.dayTemps(d)
        if (t.isEmpty()) return FloatArray(24) { 0.5f }
        var lo = t.filter { !it.isNaN() }.minOrNull() ?: 0.0
        var hi = t.filter { !it.isNaN() }.maxOrNull() ?: 1.0
        if (hi - lo < 6) { val m = (hi + lo) / 2; lo = m - 3; hi = m + 3 }
        return FloatArray(24) { i -> val v = t.getOrElse(i) { t.last() }; if (v.isNaN()) 0.5f else ((v - lo) / (hi - lo)).toFloat() }
    }

    private fun currentFractions(f: Forecast): FloatArray {
        val to = fractions(f, day)
        val from = fromFrac ?: return to
        val m = morph?.value ?: 1f
        return FloatArray(24) { from[it] + (to[it] - from[it]) * m }
    }

    private fun drawCurve(c: Canvas, L: Lay, f: Forecast, current: Boolean, ea: Float, k: Float) {
        val r = L.plot
        // Label: which day, and the way back to today when another day is shown
        ink.label.color = alpha(Palette.MUTED, ea * (0.25f + 0.75f * k))
        val title = if (day == 0) "TODAY" else Weather.dayName(f.dDate[day], day, short = false).uppercase(Locale.UK)
        c.drawText(title, r.left, L.curve.top + capHeight(ink.label), ink.label)
        if (day != 0 && current) {
            ink.meta.color = alpha(Palette.CHAMPAGNE, ea)
            val s = "Back to today"
            c.drawText(s, r.right - ink.meta.measureText(s), L.curve.top + capHeight(ink.meta), ink.meta)
        }

        val fr = currentFractions(f)
        val top = r.top + 34 * dp; val bottom = r.bottom - 8 * dp
        val xs = FloatArray(24) { r.left + r.width() * it / 23f }
        val ys = FloatArray(24) { bottom - (bottom - top) * fr[it] }
        // The membrane yields under a finger: a soft lift that falls off either side
        if (current && bump.x > 0.001f && scrubX >= 0) {
            val sigma = 30 * dp
            for (i in 0 until 24) {
                val d = (xs[i] - scrubX) / sigma
                ys[i] -= 7 * dp * bump.x * exp(-d * d).toFloat()
            }
        }
        curvePath.reset()
        curvePath.moveTo(xs[0], ys[0])
        for (i in 0 until 23) {
            val x0 = xs[maxOf(i - 1, 0)]; val y0 = ys[maxOf(i - 1, 0)]
            val x3 = xs[minOf(i + 2, 23)]; val y3 = ys[minOf(i + 2, 23)]
            val c1x = xs[i] + (xs[i + 1] - x0) / 6f; val c1y = ys[i] + (ys[i + 1] - y0) / 6f
            val c2x = xs[i + 1] - (x3 - xs[i]) / 6f; val c2y = ys[i + 1] - (y3 - ys[i]) / 6f
            curvePath.cubicTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1])
        }

        // The climax: the line draws through the day and the sun seats at now
        val e = entrance
        val reveal = if (e != null && current) e.at(120f * ornament, 620f) else 1f
        measure.setPath(curvePath, false)
        val len = measure.length
        pastPath.reset()
        val nowH = if (day == 0) (f.now % 24) else -1
        val nowFrac = if (nowH >= 0) nowH / 23f else 0f
        // Past hours faint, the rest full
        line.strokeWidth = 1.7f * dp
        if (nowH > 0) {
            measure.getSegment(0f, len * min(nowFrac, reveal), pastPath, true)
            line.color = alpha(Palette.IVORY, 0.32f * ea); c.drawPath(pastPath, line)
            pastPath.reset()
        }
        if (reveal > nowFrac || nowH <= 0) {
            measure.getSegment(len * (if (nowH > 0) nowFrac else 0f), len * reveal, pastPath, true)
            line.color = alpha(Palette.IVORY, 0.94f * ea); c.drawPath(pastPath, line)
        }

        // Axis: a hairline, brighter between sunrise and sunset
        val axisY = r.bottom + 6 * dp
        line.strokeWidth = 1f * dp; line.color = alpha(Palette.IVORY, 0.14f * ea)
        c.drawLine(r.left, axisY, r.right, axisY, line)
        val rise = f.minutes(f.dRise.getOrElse(day) { "" }); val set = f.minutes(f.dSet.getOrElse(day) { "" })
        if (rise in 0 until set) {
            line.color = alpha(Palette.CHAMPAGNE, 0.5f * ea)
            c.drawLine(r.left + r.width() * (rise / 60f) / 23f, axisY, r.left + r.width() * min(23f, set / 60f) / 23f, axisY, line)
        }
        // Hour anchors, fading near a scrubbing finger
        ink.meta.color = Palette.MUTED
        for (h in intArrayOf(0, 6, 12, 18, 23)) {
            val x = xs[h]
            val near = if (current && scrubX >= 0) (abs(x - scrubX) / (40 * dp)).coerceIn(0f, 1f) else 1f
            ink.meta.color = alpha(Palette.MUTED, ea * near * (0.3f + 0.7f * k))
            val s = if (h == 23) "23" else "%02d".format(h)
            val tw = ink.meta.measureText(s)
            val tx = when (h) { 0 -> x; 23 -> x - tw; else -> x - tw / 2 }
            c.drawText(s, tx, axisY + 18 * dp, ink.meta)
        }
        // Rain: one bead per hour, as full as its chance, only where there is a chance
        for (h in 0 until 24) {
            val idx = day * 24 + h
            val pr = f.hRain.getOrElse(idx) { 0 }
            if (pr < 10) continue
            fill.color = alpha(Palette.ACQUA, ea * (0.2f + 0.8f * pr / 100f) * (0.4f + 0.6f * k))
            c.drawCircle(xs[h], axisY + 34 * dp, (1.6f + 1.4f * pr / 100f) * dp, fill)
        }
        // High and low marked where they happen
        if (!(current && scrubHour >= 0)) {
            val t = f.dayTemps(day)
            if (t.isNotEmpty()) {
                val hiI = t.indices.maxByOrNull { t[it] } ?: 0; val loI = t.indices.minByOrNull { t[it] } ?: 0
                ink.meta.color = alpha(Palette.MUTED, ea * reveal * (0.3f + 0.7f * k))
                fun mark(i: Int, above: Boolean) {
                    val s = units.t(t[i]); val tw = ink.meta.measureText(s)
                    val x = (xs[i] - tw / 2).coerceIn(r.left, r.right - tw)
                    c.drawText(s, x, if (above) ys[i] - 9 * dp else ys[i] + 18 * dp, ink.meta)
                }
                mark(hiI, true); if (loI != hiI) mark(loI, false)
            }
        }
        // Now: the sun seated on the line (the climax)
        if (nowH >= 0 && !(current && scrubHour >= 0)) {
            if (!sunKicked && reveal >= nowFrac) {
                sunKicked = true; sun.snap(0f); sun.to(1f)
                if (entrance != null) tick(HapticFeedbackConstants.CONFIRM)
            }
            val s = if (sunKicked) sun.x else 0f
            if (s > 0.001f) {
                val cx = xs[nowH] + (xs[min(23, nowH + 1)] - xs[nowH]) * (minutesNow(f) / 60f)
                val cy = yAt(xs, ys, cx)
                fill.color = alpha(Palette.CHAMPAGNE, 0.18f * ea * ornament); c.drawCircle(cx, cy, 11 * dp * s, fill)
                fill.color = alpha(Palette.CHAMPAGNE, ea); c.drawCircle(cx, cy, 5 * dp * s, fill)
            }
        }
        // Scrub: the hour under the finger
        if (current && scrubHour >= 0) {
            val cx = xs[scrubHour]; val cy = ys[scrubHour]
            line.strokeWidth = 1f * dp; line.color = alpha(Palette.CHAMPAGNE, 0.45f * bump.x)
            c.drawLine(cx, cy + 8 * dp, cx, axisY, line)
            fill.color = Palette.CHAMPAGNE; c.drawCircle(cx, cy, 5 * dp, fill)
            val idx = day * 24 + scrubHour
            val s = "${f.hourOf(idx)}  ${units.t(f.hTemp.getOrElse(idx) { Double.NaN })}"
            val tw = ink.primary.measureText(s)
            ink.primary.color = Palette.IVORY
            c.drawText(s, (cx - tw / 2).coerceIn(r.left, r.right - tw), r.top + 14 * dp, ink.primary)
        }
    }

    private fun minutesNow(f: Forecast) = (f.minutes(f.nowTime) % 60).toFloat()

    private fun yAt(xs: FloatArray, ys: FloatArray, x: Float): Float {
        val i = (((x - xs[0]) / (xs[23] - xs[0])) * 23).toInt().coerceIn(0, 22)
        val t = ((x - xs[i]) / (xs[i + 1] - xs[i])).coerceIn(0f, 1f)
        return ys[i] + (ys[i + 1] - ys[i]) * t
    }

    private fun drawRow(c: Canvas, r: RectF, f: Forecast, n: Int, chosen: Boolean, wMin: Double, wMax: Double, ea: Float, k: Float) {
        line.strokeWidth = 1f * dp; line.color = alpha(Palette.IVORY, 0.08f * ea)
        if (n > 0) c.drawLine(r.left, r.top, r.right, r.top, line)
        val cy = r.centerY()
        val name = Weather.dayName(f.dDate[n], n)
        ink.primary.color = alpha(if (chosen) Palette.CHAMPAGNE else Palette.IVORY, ea)
        c.drawText(name, r.left, cy + capHeight(ink.primary) / 2, ink.primary)
        val gx = r.left + 72 * dp
        Glyphs.draw(c, Weather.describe(f.dCode.getOrElse(n) { 3 }).second, true, gx, cy, 26 * dp, dp, ea)
        val rain = f.dRain.getOrElse(n) { 0 }
        if (rain >= 20) {
            ink.meta.color = alpha(Palette.ACQUA, ea * (0.4f + 0.6f * k))
            c.drawText("$rain%", gx + 22 * dp, cy + capHeight(ink.meta) / 2, ink.meta)
        }
        val hiS = units.t(f.dMax.getOrElse(n) { Double.NaN }); val loS = units.t(f.dMin.getOrElse(n) { Double.NaN })
        val hiW = 34 * dp
        val tR = r.right - hiW - 8 * dp
        val tL = max(r.left + 178 * dp, r.left + r.width() * 0.52f)
        ink.figures.color = alpha(Palette.IVORY, ea)
        c.drawText(hiS, r.right - ink.figures.measureText(hiS), cy + capHeight(ink.figures) / 2, ink.figures)
        ink.figures.color = alpha(Palette.MUTED, ea)
        c.drawText(loS, tL - 10 * dp - ink.figures.measureText(loS), cy + capHeight(ink.figures) / 2, ink.figures)
        // The range on the week's scale
        val span = (wMax - wMin).coerceAtLeast(1.0)
        fun x(t: Double) = tL + (tR - tL) * ((t - wMin) / span).toFloat()
        line.strokeWidth = 4 * dp; line.color = alpha(Palette.IVORY, 0.07f * ea)
        c.drawLine(tL, cy, tR, cy, line)
        line.color = alpha(if (chosen) Palette.CHAMPAGNE else Palette.IVORY, (if (chosen) 1f else 0.5f) * ea)
        val a = x(f.dMin.getOrElse(n) { wMin }); val b = x(f.dMax.getOrElse(n) { wMax })
        c.drawLine(a, cy, max(b, a + 0.5f * dp), cy, line)
        if (n == 0) {
            // Now, on today's range
            val nx = x(f.temp).coerceIn(a, max(a, b))
            fill.color = Palette.GROUND; c.drawCircle(nx, cy, 4.5f * dp, fill)
            fill.color = alpha(Palette.IVORY, ea); c.drawCircle(nx, cy, 3f * dp, fill)
        }
    }

    private fun drawTile(c: Canvas, kind: Tile, r: RectF, f: Forecast, ea: Float, k: Float) {
        fill.color = alpha(Palette.SURFACE, ea)
        c.drawRoundRect(r, 22 * dp, 22 * dp, fill)
        val padX = 16 * dp
        val label: String; val value: String; val sub: String
        val ix = r.right - 30 * dp; val iy = r.top + 30 * dp
        when (kind) {
            Tile.WIND -> {
                label = "WIND"; value = f.wind?.let { units.w(it) } ?: "–"
                sub = (f.windDir?.let { "From the " + Weather.compass(it) } ?: "") + (f.gusts?.takeIf { it >= (f.wind ?: 0.0) + 8 }?.let { " · gusts " + units.w(it).substringBefore(' ') } ?: "")
                // The needle points where the wind is going
                f.windDir?.let { Glyphs.needle(c, ix, iy, 13 * dp, (it + 180f) % 360f, dp, ea) }
            }
            Tile.SUN -> {
                val rise = f.dRise.getOrElse(0) { "" }; val set = f.dSet.getOrElse(0) { "" }
                val t = f.daylight()
                val up = t in 0f..1f
                label = if (up) "SUNSET" else "SUNRISE"
                value = if (up) set.drop(11).take(5) else (if (t > 1f) f.dRise.getOrElse(1) { rise } else rise).drop(11).take(5)
                val mins = f.minutes(set) - f.minutes(rise)
                sub = if (mins > 0) "${mins / 60} h ${"%02d".format(mins % 60)} m of daylight" else ""
                Glyphs.sunArc(c, ix, iy + 7 * dp, 13 * dp, t, dp, ea)
            }
            Tile.FEELS -> {
                label = "FEELS LIKE"; value = units.t(f.feels ?: f.temp)
                sub = f.humidity?.let { "Humidity $it%" } ?: ""
                f.humidity?.let { Glyphs.ring(c, ix, iy, 12 * dp, it / 100f, Palette.ACQUA, dp, ea) }
            }
            Tile.RAIN -> {
                val mm = f.dPrecip.getOrElse(0) { 0.0 }.takeIf { !it.isNaN() } ?: 0.0
                label = "RAIN TODAY"; value = if (mm < 0.1) "Trace" else "%.1f mm".format(Locale.UK, mm)
                val wettest = (0 until min(24, f.hRain.size)).maxByOrNull { f.hRain[it] * 1000 + (f.hPrecip.getOrElse(it) { 0.0 }.takeIf { v -> !v.isNaN() } ?: 0.0) * 10 }
                sub = wettest?.let { "Wettest around ${f.hourOf(it)}" } ?: ""
            }
            Tile.UV -> {
                val uv = f.dUv.getOrElse(0) { 0.0 }
                label = "UV TODAY"; value = uv.roundToInt().toString()
                sub = when { uv < 6 -> "Moderate"; uv < 8 -> "High"; uv < 11 -> "Very high"; else -> "Extreme" }
                Glyphs.ring(c, ix, iy, 12 * dp, (uv / 11).toFloat(), Palette.CHAMPAGNE, dp, ea)
            }
            Tile.AIR -> {
                val q = f.aqi ?: 0
                label = "AIR QUALITY"; value = q.toString()
                sub = when { q <= 60 -> "Moderate"; q <= 80 -> "Poor"; q <= 100 -> "Very poor"; else -> "Extremely poor" }
            }
            Tile.VIS -> {
                val v = f.visibility ?: 0.0
                label = "VISIBILITY"; value = if (v >= 1000) "%.1f km".format(Locale.UK, v / 1000) else "${v.roundToInt()} m"
                sub = if (v < 1000) "Fog" else "Mist"
            }
        }
        ink.label.color = alpha(Palette.MUTED, ea * (0.25f + 0.75f * k))
        c.drawText(label, r.left + padX, r.top + 18 * dp + capHeight(ink.label), ink.label)
        ink.tile.color = alpha(Palette.IVORY, ea)
        c.drawText(fit(value, ink.tile, r.width() - 2 * padX), r.left + padX, r.top + 52 * dp + capHeight(ink.tile), ink.tile)
        ink.meta.color = alpha(Palette.MUTED, ea * (0.4f + 0.6f * k))
        wrap(sub, ink.meta, r.width() - 2 * padX, 2).forEachIndexed { i, s ->
            c.drawText(s, r.left + padX, r.top + 88 * dp + capHeight(ink.meta) + i * ink.meta.textSize * 1.3f, ink.meta)
        }
    }

    private fun drawPull(c: Canvas) {
        val x = pull.x
        if (x < 0.5f) return
        val L = layFor(page)
        val share = (x / threshold()).coerceIn(0f, 1f)
        val cx = (L.aL + L.aR) / 2; val half = (L.aR - L.aL) / 2 * share
        line.strokeWidth = 1.2f * dp
        line.color = if (share >= 1f) Palette.CHAMPAGNE else alpha(Palette.IVORY, 0.4f)
        val y = topInset + 4 * dp
        c.drawLine(cx - half, y, cx + half, y, line)
    }

    private fun threshold() = 76 * dp

    private val clock = SimpleDateFormat("HH:mm", Locale.UK)

    private fun statusStamp(p: Place, f: Forecast?): String = when {
        p.id in busy -> "Updating"
        f == null -> ""
        else -> {
            val age = System.currentTimeMillis() - f.fetched
            when {
                age < 2 * 60_000 -> "Just now"
                age < 3 * 3_600_000 -> "Updated " + clock.format(Date(f.fetched))
                age < 48 * 3_600_000 -> "Updated ${age / 3_600_000} h ago"
                else -> "Updated ${age / 86_400_000} days ago"
            } + if (p.id in failed) " · offline" else ""
        }
    }

    private fun statusLine(p: Place?, failedNow: Boolean): String =
        if (failedNow) "No forecast yet. Check the connection, then pull down to try again." else "Fetching the forecast"

    // ---- touch ----
    private enum class Mode { NONE, SCRUB, VERTICAL, HORIZONTAL }
    private var mode = Mode.NONE
    private var maybeScrub = false
    private var x0 = 0f; private var y0 = 0f; private var lastY = 0f
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private fun contentY(y: Float, L: Lay) = y + scroll - pull.x * (1f - 0.5f * ((y + scroll) / L.height).coerceIn(0f, 1f))

    private fun feelSpeed(dpPerMs: Float) {
        if (!concentratoOn) { calm.to(1f); return }
        // Concentrato: above walking pace, labels and secondary text step back
        val s = ((dpPerMs / dp - 0.6f) / (1.8f - 0.6f)).coerceIn(0f, 1f)
        calm.to(1f - 0.75f * s)
    }

    private fun tick(kind: Int = HapticFeedbackConstants.CLOCK_TICK) { if (hapticsOn) performHapticFeedback(kind) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (places.isEmpty()) return false
        val L = layFor(page)
        val f = forecastOf(places[page])
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
                x0 = e.x; y0 = e.y; lastY = e.y
                scroller.forceFinished(true)
                mode = Mode.NONE
                val cy = contentY(e.y, L)
                maybeScrub = f != null && cy >= L.plot.top - 10 * dp && cy <= L.plot.bottom + 40 * dp && e.x >= L.plot.left - 12 * dp && e.x <= L.plot.right + 12 * dp
                if (maybeScrub && f != null) startScrub(e.x, L)
                pressed = L.rows.indexOfFirst { it.contains(e.x, cy) }
                if (pressed >= 0) { press.to(1f) }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                val dx = e.x - x0; val dy = e.y - y0
                if (mode == Mode.NONE && (abs(dx) > slop || abs(dy) > slop)) {
                    mode = when {
                        maybeScrub && abs(dx) >= abs(dy) -> Mode.SCRUB
                        abs(dy) > abs(dx) -> Mode.VERTICAL
                        else -> Mode.HORIZONTAL
                    }
                    if (mode != Mode.SCRUB) endScrub()
                    press.to(0f)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    lastY = e.y
                }
                when (mode) {
                    Mode.SCRUB -> moveScrub(e.x, L)
                    Mode.VERTICAL -> {
                        val d = lastY - e.y; lastY = e.y
                        if (scroll <= 0f && (d < 0 || pullRaw > 0)) {
                            pullRaw = (pullRaw - d).coerceAtLeast(0f)
                            val lim = 150 * dp
                            pull.snap(lim * (1f - exp(-pullRaw / lim)))
                            val nowArmed = pull.x >= threshold()
                            if (nowArmed != armed) { armed = nowArmed; if (armed) tick(HapticFeedbackConstants.CONTEXT_CLICK) }
                        } else {
                            scroll = (scroll + d).coerceIn(0f, maxScroll())
                            vt?.computeCurrentVelocity(1); feelSpeed(abs(vt?.yVelocity ?: 0f))
                        }
                    }
                    Mode.HORIZONTAL -> {
                        val atEdge = (dx > 0 && page == 0) || (dx < 0 && page == places.size - 1)
                        // At the first or last place the membrane gives a little, then holds
                        pageX.snap(if (atEdge) 60 * dp * (1f - exp(-abs(dx) / (180 * dp))) * Math.signum(dx) else dx)
                        vt?.computeCurrentVelocity(1); feelSpeed(abs(vt?.xVelocity ?: 0f))
                        dotsUntil = SystemClock.uptimeMillis() + 1600
                    }
                    Mode.NONE -> {}
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(e); vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f; val vy = vt?.yVelocity ?: 0f
                press.to(0f)
                when (mode) {
                    Mode.SCRUB -> endScrub()
                    Mode.VERTICAL -> {
                        if (pullRaw > 0f) {
                            if (pull.x >= threshold() && e.actionMasked == MotionEvent.ACTION_UP) onRefresh(places[page])
                            pullRaw = 0f; armed = false; pull.to(0f)
                        } else {
                            scroller.fling(0, scroll.roundToInt(), 0, (-vy).roundToInt(), 0, 0, 0, maxScroll().roundToInt(), 0, (40 * dp).roundToInt())
                        }
                    }
                    Mode.HORIZONTAL -> {
                        val w = width.toFloat()
                        val dx = pageX.x
                        val go = when {
                            (dx < -w * 0.22f || vx < -700 * dp) && page < places.size - 1 -> 1
                            (dx > w * 0.22f || vx > 700 * dp) && page > 0 -> -1
                            else -> 0
                        }
                        if (go != 0) {
                            page += go
                            pageX.snap(dx + go * w)
                            day = 0; morph = null; fromFrac = null
                            scroll = scroll.coerceAtMost(maxScroll())
                            onPage(page); describe()
                        }
                        pageX.to(0f)
                        dotsUntil = SystemClock.uptimeMillis() + 1600
                    }
                    Mode.NONE -> {
                        endScrub()
                        if (e.actionMasked == MotionEvent.ACTION_UP) tap(e.x, contentY(e.y, L), L, f)
                    }
                }
                mode = Mode.NONE
                vt?.recycle(); vt = null
                invalidate()
            }
        }
        return true
    }

    private fun startScrub(x: Float, L: Lay) {
        bump.to(1f)
        moveScrub(x, L)
    }

    private fun moveScrub(x: Float, L: Lay) {
        scrubX = x.coerceIn(L.plot.left, L.plot.right)
        val h = (((scrubX - L.plot.left) / L.plot.width()) * 23f).roundToInt().coerceIn(0, 23)
        if (h != scrubHour) { if (scrubHour >= 0) tick(); scrubHour = h }
    }

    private fun endScrub() {
        if (scrubHour < 0 && bump.target == 0f) return
        bump.to(0f); scrubHour = -1
        postDelayed({ if (scrubHour < 0) { scrubX = -1f; invalidate() } }, 260)
    }

    private fun tap(x: Float, y: Float, L: Lay, f: Forecast?) {
        if (L.header.contains(x, y) && x < L.header.left + L.header.width() * 0.7f) { onPlaces(); return }
        if (L.placesBtn.contains(x, y)) { onPlaces(); return }
        if (L.settingsBtn.contains(x, y)) { onSettings(); return }
        if (f == null) return
        if (day != 0 && y < L.curve.top + 28 * dp && y > L.curve.top - 10 * dp && x > L.plot.right - 140 * dp) { chooseDay(0, f); return }
        val row = L.rows.indexOfFirst { it.contains(x, y) }
        if (row >= 0) chooseDay(if (row == day) 0 else row, f)
    }

    private fun chooseDay(d: Int, f: Forecast) {
        if (d == day) return
        fromFrac = currentFractions(f)
        day = d
        morph = Tween(420f)
        tick()
        describe(); invalidate()
    }

    /** Spoken summary, and what the launch test reads. */
    private fun describe() {
        if (places.isEmpty()) return
        val p = places[page]; val f = forecastOf(p)
        contentDescription = if (f == null) "${p.name}. ${statusLine(p, p.id in failed)}"
        else "${p.name}, ${units.t(f.temp)}, ${Weather.describe(f.code, f.isDay).first}. ${Weather.verdict(f)}. ${Weather.weekLine(f)}"
    }

    @Suppress("unused")
    private fun dist(a: Float, b: Float) = sqrt(a * a + b * b)
}
