package com.fergusonconsultancy.cielo

import java.util.Locale
import kotlin.math.roundToInt

/*
 * Fluido in Cielo (18 Nov 2026): the screen arranges itself by what matters today. Rules only,
 * pure arithmetic over the Forecast, so every choice can be tested and explained.
 *
 *  - RAIN leads on a wet day: the rain tile comes first, the day's rain beads grow and the
 *    temperature line steps back, and the line under the verdict says when it is wettest.
 *  - LIGHT leads on a clear day: the sun tile comes first, the daylight on the axis is lit fully
 *    with sunrise and sunset written at its ends, and the line under the verdict says how long the
 *    light lasts and when golden hour begins.
 *  - PLAIN otherwise: the screen as it was.
 *
 * Nothing moves under a finger (rule of trust 1): the lead is read when the forecast arrives, not
 * while you touch it. The whole thing is off with Settings → Arrange by today.
 */
object Today {

    enum class Lead { RAIN, LIGHT, PLAIN }

    /** Hours of today still to come, from now to midnight (or the forecast's end). */
    private fun rest(f: Forecast): IntRange {
        val end = minOf(24, f.hRain.size)
        return f.now until maxOf(f.now, end)
    }

    private fun mm(f: Forecast, i: Int) = f.hPrecip.getOrElse(i) { 0.0 }.takeIf { !it.isNaN() } ?: 0.0

    /** The day's lead. Rain wins over light: a wet afternoon matters more than a bright morning. */
    fun lead(f: Forecast): Lead {
        val r = rest(f)
        if (r.isEmpty()) return Lead.PLAIN
        val wetHours = r.count { f.hRain[it] >= 50 || (mm(f, it) >= 0.3 && f.hRain[it] >= 30) }
        val total = r.sumOf { mm(f, it) }
        val wetNow = Weather.wet(f.code) && f.hRain.getOrElse(f.now) { 0 } >= 40
        if (wetNow || wetHours >= 2 || total >= 2.0) return Lead.RAIN

        // Light: dry, the sun still up (or rising later today), and the sky mostly clear
        val t = f.daylight()
        if (t > 1f) return Lead.PLAIN
        val daylightLeft = r.filter { f.hDay.getOrElse(it) { false } }
        if (daylightLeft.size < 2) return Lead.PLAIN
        val clear = daylightLeft.count { Weather.describe(f.hCode[it]).second.let { s -> s == Weather.Sky.CLEAR || s == Weather.Sky.PARTLY } }
        return if (clear * 3 >= daylightLeft.size * 2 && wetHours == 0) Lead.LIGHT else Lead.PLAIN
    }

    /** The wettest stretch still to come today: first and last wet hour, and the millimetres. */
    data class Wet(val from: Int, val to: Int, val mm: Double)

    fun wettest(f: Forecast): Wet? {
        // The verdict's own threshold (50%), so the line never starts earlier than the glance above it
        val wet = rest(f).filter { f.hRain[it] >= 50 || (mm(f, it) >= 0.3 && f.hRain[it] >= 30) }
        if (wet.isEmpty()) return null
        // The longest unbroken run (a gap of one dry hour is still the same spell)
        var best = wet.first()..wet.first(); var cur = best
        for (h in wet.drop(1)) {
            cur = if (h - cur.last <= 2) cur.first..h else h..h
            if (cur.last - cur.first > best.last - best.first) best = cur
        }
        return Wet(best.first, best.last, (best.first..best.last).sumOf { mm(f, it) })
    }

    /** Minutes since midnight as HH:mm. */
    fun clockOf(m: Int) = minutesText(m)

    private fun minutesText(m: Int) = "%02d:%02d".format(Locale.UK, (m / 60) % 24, m % 60)

    /** Golden hour starts about an hour before sunset; plain arithmetic is near enough for a line. */
    private const val GOLDEN = 60

    /**
     * One line, in plain words, written for today. It sits under the verdict, which already says
     * the one thing about the next twelve hours; this line says why the screen is arranged as it is.
     */
    fun line(f: Forecast, lead: Lead, units: Units): String {
        val feels = f.feels?.let { " · feels ${units.t(it)}" } ?: ""
        return when (lead) {
            Lead.RAIN -> {
                val w = wettest(f)
                when {
                    w == null -> Weather.describe(f.code, f.isDay).first + feels
                    w.from == w.to -> "Wettest around ${f.hourOf(w.from)}" + amount(w.mm) + feels
                    else -> "Rain ${f.hourOf(w.from)}–${f.hourOf(w.to + 1).ifEmpty { "midnight" }}" + amount(w.mm) + feels
                }
            }
            Lead.LIGHT -> {
                val rise = f.minutes(f.dRise.getOrElse(0) { "" }); val set = f.minutes(f.dSet.getOrElse(0) { "" })
                val now = f.minutes(f.nowTime)
                when {
                    set < 0 -> Weather.describe(f.code, f.isDay).first + feels
                    now < rise -> "Light from ${minutesText(rise)} until ${minutesText(set)}"
                    now < set - GOLDEN -> "Light until ${minutesText(set)} · golden hour from ${minutesText(set - GOLDEN)}"
                    else -> "Golden hour now · sunset ${minutesText(set)}"
                }
            }
            Lead.PLAIN -> Weather.describe(f.code, f.isDay).first + feels
        }
    }

    private fun amount(mm: Double) = if (mm >= 0.5) ", about ${mm.roundToInt().coerceAtLeast(1)} mm" else ""

    /** The next rain worth telling the family about: an hour index from [from], within twelve hours, at least 40%. */
    data class Next(val hour: Int, val chance: Int, val mm: Double)

    fun next(f: Forecast, from: Int = f.now): Next? {
        if (from < 0) return null
        val end = minOf(from + 13, f.hRain.size)
        var i = (from until end).firstOrNull { f.hRain[it] >= 40 } ?: return null
        // Already raining: report when this spell began, so Senso can say "Raining since"
        if (i == from) while (i > 0 && f.hRain[i - 1] >= 40 && from - i < 3) i--
        return Next(i, f.hRain[i], mm(f, i))
    }
}
