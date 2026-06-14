package me.iacn.biliroaming.utils

import android.content.SharedPreferences
import android.net.Uri

object OfficialVideoDiagnosticsHelper {
    private const val SESSION_UPOS_HOST = "official_video_diagnostics_upos_host"
    private const val SESSION_UNTIL = "official_video_diagnostics_until"
    private const val SESSION_TTL_MS = 10 * 60 * 1000L
    private const val DIAGNOSTICS_PATH = "/blackboard/video-diagnostics.html"

    private val normalMediaUrlRegex =
        Regex("""https?://([^/"'\s\\]+)""", RegexOption.IGNORE_CASE)
    private val escapedMediaUrlRegex =
        Regex("""https?:\\/\\/([^\\/"'\s]+)""", RegexOption.IGNORE_CASE)

    fun markSession(prefs: SharedPreferences) {
        prefs.edit()
            .putString(SESSION_UPOS_HOST, selectedUposHost().orEmpty())
            .putLong(SESSION_UNTIL, System.currentTimeMillis() + SESSION_TTL_MS)
            .apply()
    }

    fun activeSessionUposHost(): String? {
        if (sPrefs.getLong(SESSION_UNTIL, 0L) < System.currentTimeMillis()) return null
        return normalizeUposHost(sPrefs.getString(SESSION_UPOS_HOST, null)) ?: selectedUposHost()
    }

    fun selectedUposHost(): String? = normalizeUposHost(sPrefs.getString("upos_host", null))

    fun isDiagnosticsPage(pageUrl: String): Boolean =
        runCatchingOrNull {
            val uri = Uri.parse(pageUrl)
            uri.host == "www.bilibili.com" && uri.path?.startsWith(DIAGNOSTICS_PATH) == true
        } ?: false

    fun replaceMediaHostInUrl(url: String, target: String): String {
        val uri = runCatchingOrNull { Uri.parse(url) } ?: return url
        val authority = uri.encodedAuthority ?: return url
        if (!authority.isDiagnosticsMediaHost()) return url
        return uri.buildUpon().authority(target).build().toString()
    }

    fun replaceMediaHostInText(text: String, target: String): String {
        if (!text.contains("bilivideo", true) &&
            !text.contains("acgvideo", true) &&
            !text.contains("akamaized", true) &&
            !text.contains("edge.mountaintoys.cn", true)
        ) return text
        val normalReplaced = normalMediaUrlRegex.replace(text) { match ->
            val host = match.groupValues[1]
            if (host.isDiagnosticsMediaHost()) match.value.replace(host, target) else match.value
        }
        return escapedMediaUrlRegex.replace(normalReplaced) { match ->
            val host = match.groupValues[1]
            if (host.isDiagnosticsMediaHost()) match.value.replace(host, target) else match.value
        }
    }

    fun String.isDiagnosticsMediaHost(): Boolean {
        val host = substringBefore(':').lowercase()
        if (host.startsWith("bvc.") ||
            host.startsWith("data.") ||
            host.startsWith("pbp.") ||
            host.startsWith("api.")
        ) return false
        return host.endsWith(".bilivideo.com") ||
                host.endsWith(".bilivideo.cn") ||
                host.endsWith(".acgvideo.com") ||
                host.endsWith(".acgvideo.cn") ||
                host == "edge.mountaintoys.cn" ||
                host.endsWith(".edge.mountaintoys.cn") ||
                host.endsWith(".akamaized.net")
    }

    private fun normalizeUposHost(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() && it != "\$1" } ?: return null
        val host = if (value.contains("://")) {
            runCatchingOrNull { Uri.parse(value).encodedAuthority } ?: return null
        } else value
        return host.trim('/').takeIf { it.isNotBlank() }
    }
}
