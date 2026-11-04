package com.fergusonconsultancy.cielo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

class Place(val id: String, val name: String, val detail: String, val lat: Double, val lon: Double, val here: Boolean = false) {
    fun json() = JSONObject().put("id", id).put("name", name).put("detail", detail).put("lat", lat).put("lon", lon).put("here", here)

    companion object {
        fun of(j: JSONObject) = Place(j.getString("id"), j.getString("name"), j.optString("detail"),
            j.getDouble("lat"), j.getDouble("lon"), j.optBoolean("here"))
        fun id(lat: Double, lon: Double) = "%.3f,%.3f".format(Locale.ROOT, lat, lon)
    }
}

/**
 * Everything Open-Meteo returned for one place, in the place's own time. Hourly arrays start at
 * 00:00 today and run for [days] days, so the day curve can show any day of the week. Anything
 * beyond temperature, sky and the daily range may be missing, and the app only shows what it has.
 */
class Forecast(
    val fetched: Long,
    val nowTime: String,
    val now: Int,
    val temp: Double, val feels: Double?, val humidity: Int?, val code: Int, val isDay: Boolean,
    val wind: Double?, val windDir: Int?, val gusts: Double?, val cloud: Int?, val visibility: Double?, val uv: Double?,
    val hTime: List<String>, val hTemp: DoubleArray, val hCode: IntArray, val hRain: IntArray, val hPrecip: DoubleArray, val hDay: BooleanArray,
    val dDate: List<String>, val dCode: IntArray, val dMax: DoubleArray, val dMin: DoubleArray, val dRain: IntArray,
    val dPrecip: DoubleArray, val dRise: List<String>, val dSet: List<String>, val dUv: DoubleArray,
    val aqi: Int?,
) {
    val days get() = dDate.size

    /** Minutes since midnight for a "yyyy-MM-ddTHH:mm" time. */
    fun minutes(t: String) = if (t.length >= 16) t.substring(11, 13).toInt() * 60 + t.substring(14, 16).toInt() else -1

    fun hourOf(i: Int) = hTime.getOrNull(i)?.substring(11, 16) ?: ""

    /** The day's 24 hourly temperatures, or fewer at the end of the forecast. */
    fun dayTemps(d: Int): DoubleArray {
        val a = (d * 24).coerceAtMost(hTemp.size)
        val b = (a + 24).coerceAtMost(hTemp.size)
        return hTemp.copyOfRange(a, b)
    }

    /** How far through daylight the place is now: 0 at sunrise, 1 at sunset; outside 0..1 at night. */
    fun daylight(): Float {
        val rise = minutes(dRise.getOrNull(0) ?: return 0.5f)
        val set = minutes(dSet.getOrNull(0) ?: return 0.5f)
        if (rise < 0 || set <= rise) return 0.5f
        return (minutes(nowTime) - rise).toFloat() / (set - rise)
    }
}

object Weather {

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12_000; conn.readTimeout = 12_000
        conn.setRequestProperty("User-Agent", "Cielo/1.0 (Android)")
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    /** Fetches, checks it reads back cleanly, keeps it. Network work: never on the main thread. */
    fun refresh(c: Context, p: Place): Forecast {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}&timezone=auto&forecast_days=8" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m," +
            "wind_direction_10m,wind_gusts_10m,cloud_cover,visibility,uv_index" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,precipitation,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum," +
            "sunrise,sunset,uv_index_max"
        val json = JSONObject(get(url))
        // Air quality is a separate service; without it everything else still shows
        runCatching {
            val aq = JSONObject(get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${p.lat}&longitude=${p.lon}&current=european_aqi&timezone=auto"))
            json.put("aq", aq.getJSONObject("current"))
        }
        json.put("fetched", System.currentTimeMillis())
        val f = parse(json)
        Store.prefs(c).edit().putString("f_" + p.id, json.toString()).apply()
        return f
    }

    fun cached(c: Context, p: Place): Forecast? =
        runCatching { parse(JSONObject(Store.prefs(c).getString("f_" + p.id, null)!!)) }.getOrNull()

    fun search(name: String): List<Place> {
        val json = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name=" + URLEncoder.encode(name, "UTF-8")))
        val r = json.optJSONArray("results") ?: return emptyList()
        return (0 until r.length()).map { i ->
            val o = r.getJSONObject(i)
            val lat = o.getDouble("latitude"); val lon = o.getDouble("longitude")
            Place(Place.id(lat, lon), o.getString("name"),
                listOfNotNull(o.optString("admin1").takeIf { it.isNotEmpty() }, o.optString("country").takeIf { it.isNotEmpty() }).joinToString(", "),
                lat, lon)
        }
    }

    private fun JSONObject.num(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
    private fun JSONArray?.d(n: Int) = DoubleArray(n) { i -> if (this == null || isNull(i)) Double.NaN else optDouble(i) }
    private fun JSONArray?.i(n: Int, default: Int = 0) = IntArray(n) { i -> if (this == null || isNull(i)) default else optInt(i) }
    private fun JSONArray?.s(n: Int) = List(n) { i -> if (this == null || isNull(i)) "" else optString(i) }

    fun parse(j: JSONObject): Forecast {
        val cur = j.getJSONObject("current")
        val h = j.getJSONObject("hourly")
        val d = j.getJSONObject("daily")
        val hn = h.getJSONArray("time").length()
        val dn = d.getJSONArray("time").length()
        val hTime = h.getJSONArray("time").s(hn)
        val nowTime = cur.getString("time")
        val now = hTime.indexOfFirst { it.take(13) == nowTime.take(13) }.coerceAtLeast(0)
        val aq = j.optJSONObject("aq")
        return Forecast(
            fetched = j.optLong("fetched", 0L), nowTime = nowTime, now = now,
            temp = cur.getDouble("temperature_2m"), feels = cur.num("apparent_temperature"),
            humidity = cur.num("relative_humidity_2m")?.roundToInt(), code = cur.optInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1, wind = cur.num("wind_speed_10m"), windDir = cur.num("wind_direction_10m")?.roundToInt(),
            gusts = cur.num("wind_gusts_10m"), cloud = cur.num("cloud_cover")?.roundToInt(), visibility = cur.num("visibility"),
            uv = cur.num("uv_index"),
            hTime = hTime, hTemp = h.getJSONArray("temperature_2m").d(hn), hCode = h.optJSONArray("weather_code").i(hn),
            hRain = h.optJSONArray("precipitation_probability").i(hn), hPrecip = h.optJSONArray("precipitation").d(hn),
            hDay = h.optJSONArray("is_day").i(hn, 1).map { it == 1 }.toBooleanArray(),
            dDate = d.getJSONArray("time").s(dn), dCode = d.optJSONArray("weather_code").i(dn),
            dMax = d.getJSONArray("temperature_2m_max").d(dn), dMin = d.getJSONArray("temperature_2m_min").d(dn),
            dRain = d.optJSONArray("precipitation_probability_max").i(dn), dPrecip = d.optJSONArray("precipitation_sum").d(dn),
            dRise = d.optJSONArray("sunrise").s(dn), dSet = d.optJSONArray("sunset").s(dn), dUv = d.optJSONArray("uv_index_max").d(dn),
            aqi = aq?.num("european_aqi")?.roundToInt(),
        )
    }

    enum class Sky { CLEAR, PARTLY, CLOUD, FOG, DRIZZLE, RAIN, SNOW, STORM }

    fun describe(code: Int, day: Boolean = true): Pair<String, Sky> = when (code) {
        0 -> (if (day) "Clear" else "Clear night") to Sky.CLEAR
        1 -> (if (day) "Mainly clear" else "Mainly clear night") to Sky.CLEAR
        2 -> "Partly cloudy" to Sky.PARTLY
        3 -> "Overcast" to Sky.CLOUD
        45 -> "Fog" to Sky.FOG
        48 -> "Freezing fog" to Sky.FOG
        51, 53 -> "Drizzle" to Sky.DRIZZLE
        55 -> "Heavy drizzle" to Sky.DRIZZLE
        56, 57 -> "Freezing drizzle" to Sky.DRIZZLE
        61 -> "Light rain" to Sky.RAIN
        63 -> "Rain" to Sky.RAIN
        65 -> "Heavy rain" to Sky.RAIN
        66, 67 -> "Freezing rain" to Sky.RAIN
        71 -> "Light snow" to Sky.SNOW
        73 -> "Snow" to Sky.SNOW
        75 -> "Heavy snow" to Sky.SNOW
        77 -> "Snow grains" to Sky.SNOW
        80 -> "Light showers" to Sky.RAIN
        81 -> "Showers" to Sky.RAIN
        82 -> "Heavy showers" to Sky.RAIN
        85, 86 -> "Snow showers" to Sky.SNOW
        95 -> "Thunderstorm" to Sky.STORM
        96, 99 -> "Thunder and hail" to Sky.STORM
        else -> "Unsettled" to Sky.CLOUD
    }

    fun wet(code: Int) = describe(code).second.let { it == Sky.RAIN || it == Sky.DRIZZLE || it == Sky.STORM || it == Sky.SNOW }

    fun dayName(date: String, index: Int, short: Boolean = true): String = when (index) {
        0 -> "Today"
        1 -> if (short) "Tmrw" else "Tomorrow"
        else -> runCatching { LocalDate.parse(date).dayOfWeek.getDisplayName(if (short) TextStyle.SHORT else TextStyle.FULL, Locale.UK) }.getOrDefault("")
    }

    /**
     * The glance: the one thing worth knowing about the next twelve hours. This is what Brolly was
     * named for, so it is the first words on the screen.
     */
    fun verdict(f: Forecast): String {
        val end = minOf(f.now + 13, f.hRain.size)
        if (end <= f.now) return describe(f.code, f.isDay).first
        val snow = (f.now until end).any { describe(f.hCode[it]).second == Sky.SNOW && f.hRain[it] >= 40 }
        val word = if (snow) "Snow" else "Rain"
        val wetNow = wet(f.code) && f.hRain[f.now] >= 40
        if (wetNow) {
            val dry = (f.now + 1 until end).firstOrNull { f.hRain[it] < 30 && !wet(f.hCode[it]) }
            return if (dry == null) "$word for the next twelve hours" else "$word easing by ${f.hourOf(dry)}"
        }
        val likely = (f.now + 1 until end).firstOrNull { f.hRain[it] >= 50 }
        if (likely != null) return (if (f.hRain[likely] >= 70) "$word from " else "$word likely from ") + f.hourOf(likely)
        val chance = (f.now + 1 until end).firstOrNull { f.hRain[it] >= 30 }
        if (chance != null) return "A chance of showers around ${f.hourOf(chance)}"
        return if (snow) "Snow possible later" else "Dry for the next twelve hours"
    }

    /** The week in one line: which days are wet, which is warmest. */
    fun weekLine(f: Forecast): String {
        val n = minOf(7, f.days)
        if (n == 0) return ""
        val wetDays = (0 until n).filter { f.dRain[it] >= 50 }
        fun name(i: Int) = dayName(f.dDate[i], i, short = false).let { if (i == 0) "today" else if (i == 1) "tomorrow" else it }
        val one = when {
            wetDays.isEmpty() -> "Dry all week."
            wetDays.size == n -> "Wet all week."
            wetDays.size == 1 -> "Wet ${name(wetDays[0])}."
            wetDays.size == 2 -> "Wet ${name(wetDays[0])} and ${name(wetDays[1])}."
            else -> "Rain on ${wetDays.size} days."
        }
        val warm = (0 until n).maxByOrNull { f.dMax[it] } ?: 0
        return "$one Warmest ${name(warm)}."
    }

    fun compass(deg: Int) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[Math.floorMod(Math.round(deg / 45f), 8)]
}

/** Places and settings, kept on the phone. */
object Store {
    fun prefs(c: Context) = c.getSharedPreferences("cielo", Context.MODE_PRIVATE)

    val LEEDS = Place(Place.id(53.7997, -1.5492), "Leeds", "England, United Kingdom", 53.7997, -1.5492)

    fun places(c: Context): MutableList<Place> {
        val raw = prefs(c).getString("places", null) ?: return mutableListOf(LEEDS)
        return runCatching {
            val a = JSONArray(raw); (0 until a.length()).map { Place.of(a.getJSONObject(it)) }.toMutableList()
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: mutableListOf(LEEDS)
    }

    fun savePlaces(c: Context, list: List<Place>) =
        prefs(c).edit().putString("places", JSONArray().apply { list.forEach { put(it.json()) } }.toString()).apply()

    var Context.fahrenheit: Boolean
        get() = prefs(this).getBoolean("fahrenheit", false)
        set(v) = prefs(this).edit().putBoolean("fahrenheit", v).apply()

    /** "mph", "kmh" or "ms". The UK default is mph. */
    var Context.windUnit: String
        get() = prefs(this).getString("wind", "mph")!!
        set(v) = prefs(this).edit().putString("wind", v).apply()

    var Context.concentrato: Boolean
        get() = prefs(this).getBoolean("concentrato", true)
        set(v) = prefs(this).edit().putBoolean("concentrato", v).apply()

    var Context.haptics: Boolean
        get() = prefs(this).getBoolean("haptics", true)
        set(v) = prefs(this).edit().putBoolean("haptics", v).apply()

    var Context.page: Int
        get() = prefs(this).getInt("page", 0)
        set(v) = prefs(this).edit().putInt("page", v).apply()
}

/** Formatting that follows the settings. */
class Units(val fahrenheit: Boolean, val wind: String) {
    fun t(c: Double): String = if (c.isNaN()) "–" else "${(if (fahrenheit) c * 9 / 5 + 32 else c).roundToInt()}°"
    fun w(kmh: Double): String = when (wind) {
        "kmh" -> "${kmh.roundToInt()} km/h"
        "ms" -> "${(kmh / 3.6).roundToInt()} m/s"
        else -> "${(kmh * 0.621371).roundToInt()} mph"
    }
}
