package me.iacn.biliroaming.utils

import android.content.SharedPreferences
import android.net.Uri
import java.io.File

object OfficialVideoDiagnosticsHelper {
    const val LOG_PREFIX = "OfficialDiagnostics"

    private const val SESSION_UPOS_HOST = "official_video_diagnostics_upos_host"
    private const val SESSION_UNTIL = "official_video_diagnostics_until"
    private const val SESSION_TTL_MS = 10 * 60 * 1000L
    private const val DIAGNOSTICS_PATH = "/blackboard/video-diagnostics.html"

    private val normalMediaUrlRegex =
        Regex("""https?://([^/"'\s\\]+)""", RegexOption.IGNORE_CASE)
    private val escapedMediaUrlRegex =
        Regex("""https?:\\/\\/([^\\/"'\s]+)""", RegexOption.IGNORE_CASE)

    fun markSession(prefs: SharedPreferences) {
        val selected = selectedUposHost()
        val until = System.currentTimeMillis() + SESSION_TTL_MS
        prefs.edit()
            .putString(SESSION_UPOS_HOST, selected.orEmpty())
            .putLong(SESSION_UNTIL, until)
            .apply()
        Log.d(
            "$LOG_PREFIX markSession process=${processName()} selected=$selected " +
                    "rawPrefs=${prefs.getString("upos_host", null)} " +
                    "rawSPrefs=${sPrefs.getString("upos_host", null)} until=$until"
        )
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

    fun describeUrlDecision(url: String, target: String): String {
        val uri = runCatchingOrNull { Uri.parse(url) }
            ?: return "decision=parse_failed target=$target url=${url.take(120)}"
        val authority = uri.encodedAuthority
            ?: return "decision=no_authority target=$target url=${url.take(120)}"
        val host = authority.substringBefore(':')
        val mediaHost = authority.isDiagnosticsMediaHost()
        val replaced = if (mediaHost) replaceMediaHostInUrl(url, target) else url
        val decision = when {
            !mediaHost -> "skip_non_media_host"
            replaced == url -> "skip_same_url"
            else -> "replace"
        }
        return "decision=$decision host=$host target=$target url=${urlForLog(url)}"
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

    fun mediaHostsInTextForLog(text: String): String {
        val hosts = (normalMediaUrlRegex.findAll(text).map { it.groupValues[1] } +
                escapedMediaUrlRegex.findAll(text).map { it.groupValues[1] })
            .filter { it.isDiagnosticsMediaHost() }
            .map { it.substringBefore(':') }
            .distinct()
            .take(8)
            .toList()
        return if (hosts.isEmpty()) "none" else hosts.joinToString()
    }

    fun sessionDebugSummary(): String {
        val now = System.currentTimeMillis()
        val until = sPrefs.getLong(SESSION_UNTIL, 0L)
        return "process=${processName()} active=${activeSessionUposHost()} " +
                "selected=${selectedUposHost()} rawSession=${sPrefs.getString(SESSION_UPOS_HOST, null)} " +
                "rawUpos=${sPrefs.getString("upos_host", null)} until=$until now=$now ttl=${until - now}"
    }

    fun urlForLog(url: String): String =
        runCatchingOrNull {
            val uri = Uri.parse(url)
            "${uri.scheme}://${uri.encodedAuthority}${uri.encodedPath.orEmpty()}"
        } ?: url.take(120)

    fun processName(): String =
        runCatchingOrNull {
            File("/proc/self/cmdline").readText().trim('\u0000').takeIf { it.isNotBlank() }
        } ?: currentContext.packageName

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
