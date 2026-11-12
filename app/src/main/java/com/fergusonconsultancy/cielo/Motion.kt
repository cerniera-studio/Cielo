package com.fergusonconsultancy.cielo

import android.content.Context
import android.os.SystemClock
import kotlin.math.abs

/**
 * A spring. Soft things (the membrane, a pressed row, the sun seating) may overshoot a touch and
 * settle; rigid things use [Tween] with one crisp deceleration and never bounce.
 */
class Spring(var x: Float, var stiffness: Float = 260f, var damping: Float = 24f, private val eps: Float = 0.25f) {
    var target = x
    var v = 0f
    val moving get() = abs(v) > eps || abs(x - target) > eps * 0.5f

    fun to(t: Float) { target = t }
    fun snap(t: Float) { x = t; target = t; v = 0f }

    /** Advances [dt] seconds. Returns true while still moving. */
    fun step(dt: Float): Boolean {
        if (!moving) { x = target; v = 0f; return false }
        var left = dt
        while (left > 0f) {
            val h = minOf(left, 1f / 240f)
            val f = -stiffness * (x - target) - damping * v
            v += f * h; x += v * h; left -= h
        }
        return true
    }
}

/** A time-based move on one crisp deceleration curve, no overshoot (the tightness rules, 24 Oct). */
class Tween(private val ms: Float, private val delayMs: Float = 0f) {
    private val start = SystemClock.uptimeMillis()
    val raw get() = ((SystemClock.uptimeMillis() - start - delayMs) / ms).coerceIn(0f, 1f)
    val value get() = EASE.getInterpolation(raw)
    val done get() = SystemClock.uptimeMillis() - start >= delayMs + ms

    /** Progress of a piece that starts [lag] ms after this tween and lasts [len] ms. */
    fun at(lag: Float, len: Float = ms): Float =
        EASE.getInterpolation(((SystemClock.uptimeMillis() - start - delayMs - lag) / len).coerceIn(0f, 1f))

    fun over(totalMs: Float) = SystemClock.uptimeMillis() - start >= delayMs + totalMs

    companion object { val EASE get() = com.cerniera.kit.Kit.ease }
}

/**
 * The hinge, from the Kit: how fast the phone was folded (Concentrato), by the family's
 * definition (the 90° crossing to the screen switching: 140 ms fast, 520 ms slow).
 */
class Hinge(c: Context) {
    private val k = com.cerniera.kit.Hinge(c, object : com.cerniera.kit.Hinge.Listener {})
    val present get() = k.present
    fun start() = k.start()
    fun stop() = k.stop()
    /** 0 for a slow fold, 1 for a fast one; 0 when the hinge told us nothing. */
    fun speed(opening: Boolean): Float = k.foldSpeed(opening)
}
