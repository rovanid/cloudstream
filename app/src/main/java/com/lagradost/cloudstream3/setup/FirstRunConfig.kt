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

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Memuat konfigurasi homepage bawaan saat aplikasi pertama kali dibuka.
 * Taruh file asli (Base64) di app/src/main/assets/homepage_config.json
 *
 * Nama prefs & key di bawah sudah dicocokkan dengan
 * MyHomepageStorageManager.kt milik plugin MyHomepage
 * (PREFS "MyHomepagePrefs", key "EXT_NAME_ON_HOME" dan "EXTENSIONS_LIST").
 * Nama field di JSON (extNameOnHome, extensionsList) memang berbeda dari key prefs.
 */
object FirstRunConfig {
    private const val FLAG_PREFS = "first_run_flags"
    private const val FLAG_KEY = "homepage_loaded"

    private const val TARGET_PREFS = "MyHomepagePrefs"
    private const val KEY_EXT_NAME_ON_HOME = "EXT_NAME_ON_HOME"
    private const val KEY_EXTENSIONS_LIST = "EXTENSIONS_LIST"

    fun applyIfFirstRun(context: Context) {
        val flags = context.getSharedPreferences(FLAG_PREFS, Context.MODE_PRIVATE)
        if (flags.getBoolean(FLAG_KEY, false)) return

        val raw = context.assets.open("homepage_config.json")
            .bufferedReader().use { it.readText() }.trim()

        // Tahap 1: Base64 -> JSON luar
        val json = JSONObject(String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8))

        // Tahap 2: "extensionsList" masih berupa string JSON; simpan apa adanya
        context.getSharedPreferences(TARGET_PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_EXT_NAME_ON_HOME, json.getBoolean("extNameOnHome"))
            putString(KEY_EXTENSIONS_LIST, json.getString("extensionsList"))
        }

        flags.edit { putBoolean(FLAG_KEY, true) }
    }
}
