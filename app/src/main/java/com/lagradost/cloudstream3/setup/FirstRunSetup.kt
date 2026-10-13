/*
 * Cloudstream VIP
 * Copyright (C) 2026 [NAMA ANDA]
 *
 * Berkas ini bagian dari Cloudstream VIP, turunan dari Cloudstream
 * (https://github.com/recloudstream/cloudstream) yang berlisensi
 * GNU General Public License versi 3. Anda dapat mendistribusikan ulang
 * dan/atau memodifikasinya sesuai ketentuan lisensi tersebut.
 * Program ini disebarkan TANPA JAMINAN apa pun.
 */
package com.lagradost.cloudstream3.setup

import android.app.Activity
import android.widget.Toast
import androidx.core.content.edit
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.delay

/**
 * Orkestrator first run: install plugin dulu, baru terapkan config homepage.
 * Flag "setup_done" hanya diset jika semuanya berhasil, jadi kalau offline
 * atau gagal, setup diulang otomatis saat aplikasi dibuka lagi.
 */
object FirstRunSetup {
    private const val PREFS = "first_run_flags"
    private const val KEY_DONE = "setup_done"
    private const val MAX_RETRY = 3

    /** Harus sama persis dengan MainAPI.name milik plugin MyHomepage. */
    private const val HOME_API_NAME = "🏠 My Homepage"

    suspend fun runIfNeeded(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return

        var ok = false
        repeat(MAX_RETRY) { attempt ->
            if (ok) return@repeat
            ok = runCatching { AutoInstallPlugins.run(activity) }.getOrDefault(false)
            if (!ok) delay(2000L * (attempt + 1)) // 2s, 4s, 6s
        }

        if (ok) {
            // Plugin sudah termuat -> prefs MyHomepage sudah ada, aman ditulis
            FirstRunConfig.applyIfFirstRun(activity)

            // Jadikan MyHomepage sebagai homepage default, lalu muat ulang Beranda
            DataStoreHelper.currentHomePage = HOME_API_NAME
            activity.runOnUiThread {
                MainActivity.afterPluginsLoadedEvent.invoke(true)
            }

            prefs.edit { putBoolean(KEY_DONE, true) }
        } else {
            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    "Setup awal gagal. Periksa koneksi internet, lalu buka ulang aplikasi.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
