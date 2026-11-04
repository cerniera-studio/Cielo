package com.fergusonconsultancy.cielo

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.widget.FrameLayout
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var sky: SkyView
    private lateinit var hinge: Hinge
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var places = mutableListOf<Place>()
    private val forecasts = HashMap<String, Forecast>()
    private val busy = HashSet<String>()
    private val failed = HashSet<String>()
    private var wide = false
    private var mainScreen = false
    private var sheet: Sheet? = null
    private var bottomInset = 0
    private var arrived = false

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        keepCrashes()
        window.setDecorFitsSystemWindows(false)
        hinge = Hinge(this)
        places = Store.places(this)
        places.forEach { p -> Weather.cached(this, p)?.let { forecasts[p.id] = it } }

        root = FrameLayout(this)
        sky = SkyView(this)
        root.addView(sky, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            sky.topInset = bars.top.toFloat(); sky.bottomInset = bars.bottom.toFloat()
            bottomInset = maxOf(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)
            sheet?.place(wide, bottomInset)
            sky.refreshData()
            insets
        }

        wide = resources.configuration.screenWidthDp >= 600
        mainScreen = resources.configuration.smallestScreenWidthDp >= 600
        sky.places = places
        sky.forecastOf = { forecasts[it.id] }
        sky.onPlaces = { showPlaces() }
        sky.onSettings = { showSettings() }
        sky.onRefresh = { refresh(it, force = true) }
        sky.onPage = { with(Store) { page = it } }
        applySettings()
        sky.setPage(with(Store) { page })
        if (forecasts.isNotEmpty()) { arrived = true; sky.arrive() }
    }

    private fun applySettings() = with(Store) {
        sky.units = Units(fahrenheit, windUnit)
        sky.hapticsOn = haptics
        sky.concentratoOn = concentrato
        sky.refreshData()
    }

    // ---- data ----
    private fun stale(p: Place) = forecasts[p.id]?.let { System.currentTimeMillis() - it.fetched > 10 * 60_000 } ?: true

    private fun refresh(p: Place, force: Boolean = false) {
        if (p.id in busy || (!force && !stale(p))) return
        busy += p.id; push()
        io.execute {
            val f = runCatching { Weather.refresh(this, p) }.getOrNull()
            main.post {
                busy -= p.id
                if (f != null) { forecasts[p.id] = f; failed -= p.id } else failed += p.id
                push()
                if (f != null && !arrived) { arrived = true; sky.arrive() }
                (sheet as? PlacesSheet)?.render()
            }
        }
    }

    private fun refreshAll() {
        // The place on screen first, then the rest
        places.getOrNull(sky.page)?.let { refresh(it) }
        places.forEach { refresh(it) }
    }

    private fun push() {
        sky.busy = busy.toSet(); sky.failed = failed.toSet()
        sky.refreshData()
    }

    private val ticker = object : Runnable {
        override fun run() { refreshAll(); sky.invalidate(); main.postDelayed(this, 60_000) }
    }

    override fun onResume() {
        super.onResume()
        hinge.start()
        main.removeCallbacks(ticker); main.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        hinge.stop()
        main.removeCallbacks(ticker)
    }

    // ---- the fold ----
    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        wide = c.screenWidthDp >= 600
        // A fold changes the screen itself (its shortest side), not just which way up it is
        val nowMain = c.smallestScreenWidthDp >= 600
        if (nowMain != mainScreen) {
            mainScreen = nowMain
            sheet?.close()
            sky.folded(open = nowMain, speed = hinge.speed(opening = nowMain))
        }
        sky.refreshData()
    }

    // ---- sheets ----
    private fun show(s: Sheet) {
        sheet?.close()
        sheet = s
        s.onClosed = { if (sheet === s) sheet = null }
        root.addView(s, FrameLayout.LayoutParams(-1, -1))
        s.post { s.place(wide, bottomInset); s.open() }
    }

    private fun showPlaces() {
        lateinit var ps: PlacesSheet
        ps = PlacesSheet(this,
            places = { places }, current = { sky.page },
            tempOf = { p -> forecasts[p.id]?.let { sky.units.t(it.temp) } },
            search = { q, done -> io.execute { val r = runCatching { Weather.search(q) }.getOrNull(); main.post { done(r) } } },
            add = { p ->
                val at = places.indexOfFirst { it.id == p.id }
                if (at < 0) { places += p; Store.savePlaces(this, places) }
                choose(if (at < 0) places.size - 1 else at)
                refresh(p, force = true)
                ps.render()
            },
            pick = { i -> choose(i); ps.close() },
            remove = { i ->
                if (places.size > 1) {
                    val gone = places.removeAt(i)
                    forecasts.remove(gone.id)
                    Store.savePlaces(this, places)
                    sky.places = places
                    sky.setPage(minOf(sky.page, places.size - 1))
                    ps.render()
                }
            },
            locate = { askLocation(ps) },
        )
        show(ps)
    }

    private fun choose(i: Int) {
        sky.places = places
        sky.refreshData()
        sky.setPage(i, animate = true)
        with(Store) { page = i }
    }

    private fun showSettings() = show(SettingsSheet(this, "1.0." + versionCode()) { applySettings() })

    private fun versionCode() = runCatching { packageManager.getPackageInfo(packageName, 0).longVersionCode }.getOrDefault(0L)

    @Deprecated("Back closes a sheet first")
    override fun onBackPressed() {
        val s = sheet
        if (s != null && !s.closing) s.close() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---- location, only when asked for ----
    private var locating: PlacesSheet? = null

    private fun askLocation(ps: PlacesSheet) {
        locating = ps
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate(ps)
        else requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 7)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        val ps = locating ?: return
        if (code == 7 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) locate(ps)
        else ps.say("Location is off for Cielo. Search for the place instead, or allow location in the phone's settings.")
    }

    @Suppress("MissingPermission")
    private fun locate(ps: PlacesSheet) {
        ps.say("Finding where you are")
        val lm = getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (provider == null) { ps.say("Location services are off. Turn them on, or search for the place instead."); return }
        var done = false
        fun use(loc: Location?) {
            if (done) return
            done = true
            if (loc == null) { ps.say("Couldn't find where you are. Search for the place instead."); return }
            io.execute {
                val name = runCatching {
                    @Suppress("DEPRECATION")
                    Geocoder(this, Locale.UK).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let { it.locality ?: it.subAdminArea }
                }.getOrNull() ?: "Here"
                main.post {
                    val p = Place("here", name, "Your location", loc.latitude, loc.longitude, here = true)
                    val at = places.indexOfFirst { it.here }
                    if (at >= 0) { places[at] = p; forecasts.remove("here") } else places.add(0, p)
                    Store.savePlaces(this, places)
                    choose(places.indexOfFirst { it.here })
                    refresh(p, force = true)
                    ps.say(""); ps.render()
                }
            }
        }
        runCatching { lm.getCurrentLocation(provider, null, mainExecutor) { use(it) } }
            .onFailure { use(runCatching { lm.getLastKnownLocation(provider) }.getOrNull()) }
        main.postDelayed({ if (!done) use(runCatching { lm.getLastKnownLocation(provider) }.getOrNull()) }, 12_000)
    }

    // ---- crashes are kept and shown in the launch test ----
    private fun keepCrashes() {
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { File(filesDir, "crash.txt").writeText(e.stackTraceToString()) }
            prior?.uncaughtException(t, e)
        }
    }
}
