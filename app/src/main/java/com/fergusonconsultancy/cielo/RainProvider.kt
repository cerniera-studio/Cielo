package com.fergusonconsultancy.cielo

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.util.concurrent.Executors

/**
 * Cielo shares its next rain with the family (Fluido, 18 Nov 2026). Senso, in the Kit, reads
 * `content://com.cerniera.cielo.rain/next`: one row with `starts_at` (epoch ms), `chance` (0 to 1)
 * and `mm`. No row means no rain in sight in the next twelve hours, or nothing fresh to say.
 *
 * Only times and chances leave Cielo, never the place. The forecast is the home place: "Your
 * location" when it is set, otherwise the first place. Settings → Share rain turns it off.
 * Read only; it never fetches (that is [RainJob]'s work), so a query is instant.
 */
class RainProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { RainJob.schedule(it) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val out = MatrixCursor(COLUMNS)
        val c = context ?: return out
        next(c, System.currentTimeMillis())?.let { (at, chance, mm) -> out.addRow(arrayOf<Any>(at, chance, mm)) }
        out.setNotificationUri(c.contentResolver, URI)
        return out
    }

    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.cerniera.rain"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.cerniera.cielo.rain"
        val URI: Uri = Uri.parse("content://$AUTHORITY/next")
        val COLUMNS = arrayOf("starts_at", "chance", "mm")
        /** A forecast older than this says nothing (better silent than wrong). */
        const val FRESH = 6 * 3_600_000L

        fun home(c: Context): Place = Store.places(c).let { ps -> ps.firstOrNull { it.here } ?: ps.first() }

        /** The next rain from the home place's cached forecast: (starts_at, chance, mm), or null. */
        fun next(c: Context, now: Long): Triple<Long, Float, Float>? {
            if (!with(Store) { c.shareRain }) return null
            val f = Weather.cached(c, home(c)) ?: return null
            if (now - f.fetched > FRESH) return null
            val n = Today.next(f, f.hourAt(now)) ?: return null
            val at = f.epoch(n.hour).takeIf { it > 0 } ?: return null
            return Triple(at, n.chance / 100f, n.mm.toFloat())
        }
    }
}

/**
 * Keeps the home place's forecast fresh while Cielo is closed, so the rain Senso reads is never
 * stale: once an hour, only with a network, and the phone decides exactly when (battery first).
 */
class RainJob : JobService() {
    private val io = Executors.newSingleThreadExecutor()

    override fun onStartJob(p: JobParameters): Boolean {
        if (!with(Store) { shareRain }) return false
        io.execute {
            val ok = runCatching { Weather.refresh(this, RainProvider.home(this)) }.isSuccess
            jobFinished(p, !ok)
        }
        return true
    }

    override fun onStopJob(p: JobParameters) = true

    companion object {
        private const val ID = 4107

        fun schedule(c: Context) = runCatching {
            val js = c.getSystemService(JobScheduler::class.java) ?: return@runCatching
            if (js.getPendingJob(ID) != null) return@runCatching
            js.schedule(JobInfo.Builder(ID, ComponentName(c, RainJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(60 * 60_000L, 20 * 60_000L)
                .setPersisted(true)
                .build())
        }

        fun cancel(c: Context) = runCatching { c.getSystemService(JobScheduler::class.java)?.cancel(ID) }
    }
}
