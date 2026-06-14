package me.iacn.biliroaming.utils

import android.net.Uri

object RecentPlaybackUrlStore {
    private const val KEY_URL = "speed_test_recent_playback_url"
    private const val KEY_URLS = "speed_test_recent_playback_urls"
    private const val KEY_HOST = "speed_test_recent_playback_host"
    private const val KEY_SOURCE = "speed_test_recent_playback_source"
    private const val KEY_UPDATED_AT = "speed_test_recent_playback_updated_at"
    private const val MAX_AGE_MS = 30L * 60L * 1000L

    data class RecentPlaybackUrl(
        val url: String,
        val urls: List<String>,
        val host: String,
        val source: String,
        val updatedAt: Long
    ) {
        val ageMs: Long
            get() = System.currentTimeMillis() - updatedAt
    }

    fun save(url: String, source: String) {
        saveAll(listOf(url), source)
    }

    fun saveAll(urls: List<String>, source: String) {
        val normalized = urls.asSequence()
            .filter { it.startsWith("http", ignoreCase = true) }
            .distinct()
            .toList()
        val url = normalized.firstOrNull() ?: return
        val uri = runCatchingOrNull { Uri.parse(url) } ?: return
        val host = uri.encodedAuthority?.takeIf { it.isNotBlank() } ?: return
        sPrefs.edit()
            .putString(KEY_URL, url)
            .putString(KEY_URLS, normalized.joinToString("\n"))
            .putString(KEY_HOST, host)
            .putString(KEY_SOURCE, source)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
        Log.d(
            "RecentPlaybackUrl: source=$source count=${normalized.size} " +
                    "hosts=${normalized.take(4).joinToString { it.hostForLog() }} " +
                    "path=${uri.encodedPath?.takeLast(96).orEmpty()}"
        )
    }

    fun latest(): RecentPlaybackUrl? {
        val url = sPrefs.getString(KEY_URL, null).orEmpty()
        val urls = sPrefs.getString(KEY_URLS, null)
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.toList()
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(url)
        val host = sPrefs.getString(KEY_HOST, null).orEmpty()
        val source = sPrefs.getString(KEY_SOURCE, null).orEmpty()
        val updatedAt = sPrefs.getLong(KEY_UPDATED_AT, 0L)
        if (url.isBlank() || host.isBlank() || updatedAt <= 0L) return null
        return RecentPlaybackUrl(url, urls, host, source, updatedAt)
    }

    fun latestForHost(host: String): RecentPlaybackUrl? =
        latest()?.takeIf {
            it.ageMs in 0..MAX_AGE_MS && it.host.equals(host, ignoreCase = true)
        }

    private fun String.hostForLog(): String =
        runCatchingOrNull { Uri.parse(this).encodedAuthority }
            ?.takeIf { it.isNotBlank() }
            ?: take(120)
}
