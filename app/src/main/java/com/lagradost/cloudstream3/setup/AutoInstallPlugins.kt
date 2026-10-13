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
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData

/**
 * Tambah repo + install plugin otomatis saat first run.
 * Disesuaikan dengan RepositoryManager & PluginManager di fork ini.
 */
object AutoInstallPlugins {
    private const val REPO_NAME = "My Repo"
    private const val REPO_URL =
        "https://raw.githubusercontent.com/rovanid/M3U-Playlist-Player-Repo-for-Cloudstream/builds/xr3ed-jsdelivr.json"

    // internalName dari plugins-jsdelivr.json
    private val REQUIRED = setOf("AnimeSailXR", "xr3edFlix", "MyHomepage")

    suspend fun run(activity: Activity): Boolean {
        // RepositoryData punya 3 field (iconUrl, name, url); pakai named argument.
        // Cek definisinya di fork Anda kalau ada error di baris ini.
        val repo = RepositoryData(iconUrl = null, name = REPO_NAME, url = REPO_URL)

        // 1. Daftarkan repo (tanpa duplikat)
        RepositoryManager.addRepository(repo)

        // 2. Ambil daftar plugin dari repo, filter yang dibutuhkan
        val plugins = RepositoryManager.getRepoPlugins(repo) ?: return false
        val targets = plugins
            .filter { it.plugin.internalName in REQUIRED }
            .distinctBy { it.plugin.internalName }
        if (targets.size < REQUIRED.size) return false // ada plugin yang belum ketemu

        // true hanya jika SEMUA plugin berhasil diunduh dan dimuat
        return targets.all { w ->
            PluginManager.downloadPlugin(
                activity,
                w.plugin.url,
                w.plugin.fileHash,
                w.plugin.internalName,
                w.repositoryData.url,
                true // loadPlugin
            )
        }
    }
}
