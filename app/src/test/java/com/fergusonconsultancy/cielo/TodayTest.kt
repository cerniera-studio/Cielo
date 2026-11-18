package com.fergusonconsultancy.cielo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayTest {
    /** A day of 24 hours from midnight; [now] is the hour on the clock. */
    private fun day(now: Int, rain: IntArray = IntArray(24), mm: DoubleArray = DoubleArray(24), code: Int = 0, codes: IntArray? = null) = Forecast(
        fetched = 0, nowTime = "2026-11-19T%02d:10".format(now), now = now,
        temp = 8.0, feels = 6.0, humidity = 80, code = code, isDay = now in 8..15,
        wind = 10.0, windDir = 200, gusts = null, cloud = 20, visibility = null, uv = 1.0,
        hTime = List(24) { "2026-11-19T%02d:00".format(it) }, hTemp = DoubleArray(24) { 5.0 + it % 6 },
        hCode = codes ?: IntArray(24) { code }, hRain = rain, hPrecip = mm, hDay = BooleanArray(24) { it in 8..15 },
        dDate = listOf("2026-11-19"), dCode = intArrayOf(code), dMax = doubleArrayOf(10.0), dMin = doubleArrayOf(4.0),
        dRain = intArrayOf(rain.maxOrNull() ?: 0), dPrecip = doubleArrayOf(mm.sum()), dRise = listOf("2026-11-19T07:41"),
        dSet = listOf("2026-11-19T16:02"), dUv = doubleArrayOf(1.0), aqi = null, offset = 0,
    )

    @Test fun wetAfternoonLeadsWithRain() {
        val rain = IntArray(24) { if (it in 15..18) 80 else 5 }
        val mm = DoubleArray(24) { if (it in 15..18) 1.2 else 0.0 }
        val f = day(10, rain, mm, codes = IntArray(24) { if (it in 15..18) 63 else 2 })
        assertEquals(Today.Lead.RAIN, Today.lead(f))
        val w = Today.wettest(f)!!
        assertEquals(15, w.from); assertEquals(18, w.to)
        assertEquals("Wettest 15:00–19:00, about 5 mm · feels 6°", Today.line(f, Today.Lead.RAIN, Units(false, "mph")))
    }

    @Test fun clearDayLeadsWithLight() {
        val f = day(10, code = 0)
        assertEquals(Today.Lead.LIGHT, Today.lead(f))
        assertEquals("Light until 16:02 · golden hour from 15:02", Today.line(f, Today.Lead.LIGHT, Units(false, "mph")))
    }

    @Test fun greyDryDayIsPlain() {
        assertEquals(Today.Lead.PLAIN, Today.lead(day(10, code = 3)))
    }

    @Test fun clearNightIsPlain() {
        assertEquals(Today.Lead.PLAIN, Today.lead(day(20, code = 0)))
    }

    @Test fun nextRainForSenso() {
        val rain = IntArray(24) { if (it in 14..16) 70 else 0 }
        val f = day(10, rain)
        val n = Today.next(f, 10)!!
        assertEquals(14, n.hour); assertEquals(70, n.chance)
        assertEquals(f.epoch(14), f.epoch(0) + 14 * 3_600_000L)
        assertNull(Today.next(day(10), 10))
    }

    @Test fun alreadyRainingReportsWhenItBegan() {
        val rain = IntArray(24) { if (it in 8..12) 80 else 0 }
        val n = Today.next(day(10, rain), 10)!!
        assertEquals(8, n.hour)
    }

    @Test fun hourAtFindsTheHour() {
        val f = day(10)
        assertEquals(13, f.hourAt(f.epoch(13) + 25 * 60_000L))
        assertTrue(f.hourAt(f.epoch(0) - 1) < 0)
    }
}
