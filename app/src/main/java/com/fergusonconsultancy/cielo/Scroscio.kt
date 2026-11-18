package com.fergusonconsultancy.cielo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Scroscio, first step (18 Nov 2026): is there real quarter-hour rain for this place?
 *
 * Open-Meteo has native 15-minute rain only from DWD's ICON-D2 (Central Europe, 2 km). Elsewhere
 * its default answer is interpolated, which in practice is hourly rain spread over four slots.
 * This asks both, side by side, for the next two hours, and keeps a log to compare with the window.
 * Nothing is drawn from it yet.
 */
object Scroscio {

    class Series(val name: String, val mm: DoubleArray?, val error: String?) {
        /** Values that come in identical runs of four on the hour look spread from hourly data. */
        val looksHourly: Boolean
            get() = mm != null && mm.any { it > 0.0 } && mm.toList().chunked(4).all { q -> q.all { it == q[0] } }
        val wet get() = mm?.any { it >= 0.1 } == true
    }

    class Check(val at: String, val place: String, val times: List<String>, val icon: Series, val default: Series)

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12_000; conn.readTimeout = 12_000
        conn.setRequestProperty("User-Agent", "Cielo/1.0 (Android)")
        try {
            if (conn.responseCode !in 200..299) {
                val why = runCatching { JSONObject(conn.errorStream.bufferedReader().readText()).optString("reason") }.getOrNull()
                throw java.io.IOException(why?.takeIf { it.isNotEmpty() } ?: "HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    private fun ask(p: Place, model: String?): Pair<List<String>, Series> {
        val name = if (model == null) "Default" else "ICON-D2"
        return runCatching {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}&timezone=auto" +
                "&minutely_15=precipitation&forecast_minutely_15=8" + (model?.let { "&models=$it" } ?: "")
            val m = JSONObject(get(url)).getJSONObject("minutely_15")
            val t = m.getJSONArray("time")
            // The key is "precipitation", or carries the model's name when one is asked for
            val key = m.keys().asSequence().firstOrNull { it.startsWith("precipitation") }
            val a = key?.let { m.getJSONArray(it) }
            val times = (0 until t.length()).map { t.getString(it).takeLast(5) }
            val mm = if (a == null || (0 until a.length()).all { a.isNull(it) }) null
                else DoubleArray(a.length()) { if (a.isNull(it)) Double.NaN else a.getDouble(it) }
            times to Series(name, mm, if (mm == null) "no values here" else null)
        }.getOrElse { emptyList<String>() to Series(name, null, it.message ?: "not reachable") }
    }

    /** Network work: never on the main thread. Logs the check. */
    fun check(c: Context, p: Place): Check {
        val (t1, icon) = ask(p, "icon_d2")
        val (t2, def) = ask(p, null)
        val at = LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK))
        val check = Check(at, p.name, t2.ifEmpty { t1 }, icon, def)
        log(c, check)
        return check
    }

    // ---- the log: the last 120 checks, kept on the phone, to copy into a chat ----
    private const val KEY = "scroscio_log"
    private const val KEEP = 120

    private fun Series.text() = mm?.joinToString(" ") { if (it.isNaN()) "–" else "%.1f".format(Locale.ROOT, it) } ?: "($error)"

    private fun log(c: Context, k: Check) {
        val a = runCatching { JSONArray(Store.prefs(c).getString(KEY, "[]")) }.getOrDefault(JSONArray())
        val line = "${k.at} · ${k.place} · from ${k.times.firstOrNull() ?: "?"}\n" +
            "  ICON-D2: ${k.icon.text()}\n  Default: ${k.default.text()}" + (if (k.default.looksHourly) "  (spread from hourly)" else "")
        a.put(line)
        val keep = JSONArray(); for (i in maxOf(0, a.length() - KEEP) until a.length()) keep.put(a.getString(i))
        Store.prefs(c).edit().putString(KEY, keep.toString()).apply()
    }

    fun logSize(c: Context) = runCatching { JSONArray(Store.prefs(c).getString(KEY, "[]")).length() }.getOrDefault(0)

    fun logText(c: Context): String {
        val a = runCatching { JSONArray(Store.prefs(c).getString(KEY, "[]")) }.getOrDefault(JSONArray())
        val head = "Cielo · Scroscio rain log (mm per quarter-hour, next two hours)\n"
        return head + (0 until a.length()).joinToString("\n\n") { a.getString(it) }
    }

    fun clear(c: Context) = Store.prefs(c).edit().remove(KEY).apply()

    /** One plain line for the sheet: what this check says about the data. */
    fun verdict(k: Check): String = when {
        k.icon.mm == null && k.default.mm == null -> "Neither source answered. Check the connection and try again."
        k.icon.mm == null -> "ICON-D2 has no values here, so this place is outside its area. The default " +
            (if (k.default.looksHourly) "looks spread from hourly data." else "is the only quarter-hour source.")
        !k.icon.wet && !k.default.wet -> "Dry for the next two hours in both. Check again on a wet day."
        else -> "ICON-D2 answers here, so quarter-hour rain is real for this place. Compare both with the window."
    }
}
