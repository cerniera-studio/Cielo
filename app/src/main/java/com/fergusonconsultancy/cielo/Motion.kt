package com.fergusonconsultancy.cielo

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.animation.PathInterpolator
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

    companion object { val EASE = PathInterpolator(0.2f, 0f, 0f, 1f) }
}

/**
 * Reads the hinge so the app knows how fast the phone was folded (Concentrato). Opening speed is
 * the time from the hinge passing 90° to the screen switching: 140 ms or less is fast, 520 ms or
 * more is slow (the same thresholds as Fold Motion). Closing reads the same crossing downwards.
 */
class Hinge(c: Context) : SensorEventListener {
    private val sm = c.getSystemService(SensorManager::class.java)
    // The Android emulator (ranchu) brings itself down when its virtual hinge is read on a phone
    // profile (seen on API 34 in the launch test), so the hinge is only read on real hardware
    private val sensor: Sensor? = if (android.os.Build.HARDWARE == "ranchu") null else sm?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
    private val times = LongArray(64)
    private val angles = FloatArray(64)
    private var n = 0
    val present get() = sensor != null

    fun start() { sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { sm?.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        val i = n % times.size
        times[i] = SystemClock.uptimeMillis(); angles[i] = e.values[0]; n++
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /** 0 for a slow fold, 1 for a fast one; 0 when the hinge told us nothing. */
    fun speed(opening: Boolean): Float {
        val now = SystemClock.uptimeMillis()
        val count = minOf(n, times.size)
        for (k in 1 until count) {
            val a = (n - k) % times.size; val b = (n - k - 1 + times.size) % times.size
            if (now - times[a] > 1500) break
            val crossed = if (opening) angles[b] < 90f && angles[a] >= 90f else angles[b] > 90f && angles[a] <= 90f
            if (crossed) {
                val ms = (now - times[a]).toFloat()
                return (1f - (ms - 140f) / (520f - 140f)).coerceIn(0f, 1f)
            }
        }
        return 0f
    }
}
