@file:Suppress("DEPRECATION")

package me.iacn.biliroaming

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import kotlinx.coroutines.*
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.UposReplaceHelper.hostForLog
import me.iacn.biliroaming.utils.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class SpeedTestResult(val name: String, val value: String, var speed: String)

class SpeedTestAdapter(context: Context) : ArrayAdapter<SpeedTestResult>(context, 0) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return (convertView ?: context.inflateLayout(R.layout.cdn_speedtest_item)).apply {
            getItem(position).let {
                findViewById<TextView>(R.id.upos_name).text = it?.name
                findViewById<TextView>(R.id.upos_speed).text =
                    XposedInit.moduleRes.getString(R.string.speed_formatter, it?.speed)
            }
        }
    }

    fun sort() = sort { a, b ->
        val aSpeed = a.speed.toLongOrNull()
        val bSpeed = b.speed.toLongOrNull()
        if (aSpeed == null && bSpeed == null)
            0
        else if (aSpeed == null)
            1
        else if (bSpeed == null)
            -1
        else
            (bSpeed - aSpeed).toInt()
    }
}

class SpeedTestDialog(activity: Activity, private val prefs: SharedPreferences) :
    AlertDialog.Builder(activity) {
    private val scope = MainScope()
    private val speedTestDispatcher = Executors.newFixedThreadPool(1).asCoroutineDispatcher()

    private val view = ListView(activity)
    private val adapter = SpeedTestAdapter(activity)

    private data class SpeedTestSource(
        val rawUrl: String,
        val referer: String,
        val label: String,
        val userAgent: String,
        val replaceHost: Boolean,
        val webHeaders: Boolean
    )

    private data class MediaUrlCandidate(
        val url: String,
        val typePriority: Int,
        val bandwidth: Int,
        val kind: String
    )

    init {
        view.adapter = adapter

        view.addHeaderView(context.inflateLayout(R.layout.cdn_speedtest_item).apply {
            findViewById<TextView>(R.id.upos_name).text = XposedInit.moduleRes.getString(R.string.upos)
            findViewById<TextView>(R.id.upos_speed).text = XposedInit.moduleRes.getString(R.string.speed)
        }, null, false)

        view.setPadding(16.dp, 10.dp, 16.dp, 10.dp)

        setView(view)

        setPositiveButton("关闭", null)

        setOnDismissListener {
            scope.cancel()
            speedTestDispatcher.close()
        }

        view.setOnItemClickListener { _, _, pos, _ ->
            val (name, value, _) = adapter.getItem(pos - 1/*headerView*/)
                ?: return@setOnItemClickListener
            Log.d("Use UPOS Server $name: $value")
            prefs.edit().putString("upos_host", value).apply()
            Log.toast("已启用 UPOS 服务器：${name}", force = true)
        }

        setTitle("CDN 测速")
    }

    override fun show(): AlertDialog {
        val dialog = super.show()
        scope.launch {
            dialog.setTitle("正在获取最近播放视频……")
            val target = selectedCdnHost()
            val recent = RecentVideoStore.latest() ?: run {
                dialog.setTitle("请先播放一个视频后再测速")
                Log.toast("未找到最近播放视频，先播放一个普通视频后再测速", force = true)
                return@launch
            }
            val source = getTestSource(target, recent) ?: run {
                dialog.setTitle("测速源不可用：${target.name}")
                Log.toast("最近视频的分片在当前 CDN 不可用，请换一个视频或 CDN 再试", force = true)
                return@launch
            }
            dialog.setTitle("正在测速：${target.name}")
            val item = SpeedTestResult(target.name, target.host, "...")
            adapter.add(item)
            item.speed = speedTest(target.host, source).toString()
            adapter.sort()
            dialog.setTitle("测速完成：${source.label}")
        }
        return dialog
    }

    private fun selectedCdnHost(): CdnHost {
        val selected = prefs.getString("upos_host", null)
            ?.takeIf { it in CdnHostRepository.values().map(CharSequence::toString) }
            ?: if (UposReplaceHelper.isLocatedCn) {
                CdnHostRepository.HW_HOST
            } else {
                CdnHostRepository.DEFAULT_HOST
            }
        return CdnHostRepository.hosts.firstOrNull { it.host == selected }
            ?: CdnHost("当前选择", selected)
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun speedTest(upos: String, source: SpeedTestSource) =
        withContext(speedTestDispatcher) {
            val url = if (!source.replaceHost || upos == "\$1") URL(source.rawUrl) else {
                URL(Uri.parse(source.rawUrl).buildUpon().authority(upos).build().toString())
            }
            val start = SystemClock.elapsedRealtime()
            var bytes = 0L
            var firstByteAt = 0L
            var responseCode = -1
            var cookieCount = 0
            val errorPreview = StringBuilder()
            var connection: HttpURLConnection? = null
            val speed = try {
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("User-Agent", source.userAgent)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Range", "bytes=0-")
                    setRequestProperty("Connection", "close")
                    if (source.webHeaders) {
                        setRequestProperty("Referer", source.referer)
                        setRequestProperty("Origin", "https://www.bilibili.com")
                        cookieCount = applyCookieHeader(url.toString(), source.referer)
                    }
                }
                connection.connect()
                responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    val stream = runCatchingOrNull { connection.errorStream ?: connection.inputStream }
                    stream?.use {
                        val buffer = ByteArray(ERROR_PREVIEW_LIMIT)
                        val read = it.read(buffer)
                        if (read > 0) {
                            errorPreview.append(String(buffer, 0, read, Charsets.UTF_8))
                        }
                    }
                    0L
                } else {
                    val stream = connection.inputStream
                    val buffer = ByteArray(32 * 1024)
                    val deadline = start + TEST_WINDOW_MS
                    stream.use {
                        while (SystemClock.elapsedRealtime() < deadline && bytes < MAX_TEST_BYTES) {
                            val read = it.read(buffer)
                            if (read <= 0) break
                            if (firstByteAt == 0L) firstByteAt = SystemClock.elapsedRealtime()
                            bytes += read
                        }
                    }
                    calculateSpeed(bytes, start)
                }
            } catch (e: Throwable) {
                debugLog {
                    "SpeedTest failed host=$upos target=${url.toString().hostForLog()} " +
                            "status=$responseCode bytes=$bytes error=${e.javaClass.simpleName}: ${e.message}"
                }
                calculateSpeed(bytes, start)
            } finally {
                connection?.disconnect()
            }
            val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
            val firstByte = firstByteAt.takeIf { it > 0 }?.let { it - start } ?: -1L
            Log.d(
                "SpeedTest host=$upos target=${url.toString().hostForLog()} status=$responseCode " +
                        "bytes=$bytes elapsed=${elapsed}ms firstByte=${firstByte}ms speed=${speed}KB/s " +
                        "cookies=$cookieCount source=${source.label}" +
                        errorPreview.takeIf { it.isNotBlank() }?.let { " body=${it.compactForLog()}" }.orEmpty()
            )
            speed
        }

    private fun calculateSpeed(bytes: Long, start: Long): Long {
        val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1L)
        return bytes * 1000L / elapsed / 1024L
    }

    private suspend fun getTestSource(
        target: CdnHost,
        recent: RecentVideoStore.RecentVideo
    ): SpeedTestSource? = try {
        withContext(speedTestDispatcher) {
            withTimeout(7000) {
                getRecentPlaybackUrl(recent, target.host)?.let { return@withTimeout it }
                val playUrl = getRecentVideoWebPlayUrl(recent, target.host)
                    ?: getRecentVideoPlayUrl(recent, target.host)
                    ?: return@withTimeout null
                SpeedTestSource(
                    rawUrl = playUrl,
                    referer = recent.referer,
                    label = recent.displayName,
                    userAgent = browserUserAgent,
                    replaceHost = true,
                    webHeaders = true
                ).also {
                    Log.d("SpeedTest source recent=${recent.displayName} url=${playUrl.hostForLog()}")
                }
            }
        }
    } catch (e: Throwable) {
        debugLog { "SpeedTest source failed: ${e.javaClass.simpleName}: ${e.message}" }
        null
    }

    private fun getRecentPlaybackUrl(
        recent: RecentVideoStore.RecentVideo,
        targetHost: String
    ): SpeedTestSource? {
        val latest = RecentPlaybackUrlStore.latest()
        if (latest == null) {
            Log.d("SpeedTest playback cache empty")
            return null
        }
        if (!latest.host.equals(targetHost, ignoreCase = true)) {
            Log.d(
                "SpeedTest playback cache mismatch target=$targetHost " +
                        "cache=${latest.host} age=${latest.ageMs}ms source=${latest.source}"
            )
            return null
        }
        val urls = latest.urls.takeIf { it.isNotEmpty() } ?: listOf(latest.url)
        for ((index, url) in urls.withIndex()) {
            val status = probeMediaUrl(url, recent.referer, appUserAgent, webHeaders = false)
            val host = url.hostForLog()
            Log.d(
                "SpeedTest playback probe index=$index host=$host status=$status " +
                        "age=${latest.ageMs}ms source=${latest.source}"
            )
            if (status in 200..299) {
                return SpeedTestSource(
                    rawUrl = url,
                    referer = recent.referer,
                    label = "${recent.displayName} / 播放缓存${if (index > 0) " backup#$index" else ""}",
                    userAgent = appUserAgent,
                    replaceHost = false,
                    webHeaders = false
                ).also {
                    Log.d(
                        "SpeedTest source playbackCache=${recent.displayName} " +
                                "host=$host index=$index age=${latest.ageMs}ms source=${latest.source}"
                    )
                }
            }
        }
        debugLog {
            "SpeedTest playback cache unusable target=$targetHost count=${urls.size} " +
                    "age=${latest.ageMs}ms source=${latest.source}"
        }
        return null
    }

    private fun getRecentVideoWebPlayUrl(
        recent: RecentVideoStore.RecentVideo,
        targetHost: String
    ): String? {
        val url = Uri.Builder()
            .scheme("https")
            .encodedAuthority("api.bilibili.com")
            .encodedPath("/x/player/playurl")
            .appendQueryParameter("bvid", recent.bvid)
            .appendQueryParameter("cid", recent.cid.toString())
            .appendQueryParameter("qn", "64")
            .appendQueryParameter("fnval", "4048")
            .appendQueryParameter("fourk", "1")
            .build()
            .toString()
        val text = requestText(url, recent.referer) ?: return null
        val json = runCatchingOrNull { JSONObject(text) } ?: return null
        if (json.optInt("code", -1) != 0) {
            debugLog {
                "SpeedTest web playurl failed code=${json.optInt("code")} message=${json.optString("message")}"
            }
            return null
        }
        val candidates = collectWebPlayUrlCandidates(json)
        Log.d(
            "SpeedTest web candidates count=${candidates.size} " +
                    "hosts=${candidates.take(8).joinToString { it.url.hostForLog() }}"
        )
        return selectUsableCandidate(
            candidates,
            targetHost,
            recent.referer,
            "web",
            browserUserAgent,
            webHeaders = true
        )
    }

    private fun requestText(url: String, referer: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                setRequestProperty("User-Agent", browserUserAgent)
                setRequestProperty("Referer", referer)
                setRequestProperty("Origin", "https://www.bilibili.com")
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Accept-Encoding", "identity")
                applyCookieHeader(url, referer)
            }
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                debugLog {
                    "SpeedTest request failed status=${connection.responseCode} url=${url.hostForLog()}"
                }
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Throwable) {
            debugLog {
                "SpeedTest request failed url=${url.hostForLog()} error=${e.javaClass.simpleName}: ${e.message}"
            }
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun getRecentVideoPlayUrl(recent: RecentVideoStore.RecentVideo, targetHost: String): String? {
        val playURLMossClass = instance.playURLMossClass ?: return null
        val playViewReqClass = instance.playViewReqClass ?: return null
        val playViewMethod = playURLMossClass.firstExistingMethodName("playView", "executePlayView")
            ?: return null
        val playViewReq = playViewReqClass.new()?.apply {
            callMethodOrNull("setAid", recent.aid)
            callMethodOrNull("setBvid", recent.bvid)
            callMethodOrNull("setCid", recent.cid)
            callMethodOrNull("setQn", 64)
            callMethodOrNull("setFnval", 4048)
            callMethodOrNull("setFourk", true)
            callMethodOrNull("setForceHost", 2)
        } ?: return null
        val playViewReply = UposReplaceHelper.withoutTfOverride {
            playURLMossClass.new()
                ?.callMethod(playViewMethod, playViewReq)
                ?.callMethodAs<ByteArray>("toByteArray")
                ?.let { UGCPlayViewReply.parseFrom(it) }
        } ?: return null
        val candidates = collectMediaCandidates(playViewReply)
        Log.d(
            "SpeedTest media candidates count=${candidates.size} " +
                    "hosts=${candidates.take(8).joinToString { it.url.hostForLog() }}"
        )
        return selectUsableCandidate(
            candidates,
            targetHost,
            recent.referer,
            "moss",
            appUserAgent,
            webHeaders = false
        )
    }

    private fun selectUsableCandidate(
        candidates: List<MediaUrlCandidate>,
        targetHost: String,
        referer: String,
        sourceName: String,
        userAgent: String,
        webHeaders: Boolean
    ): String? {
        val sorted = candidates.sortedWith(
            compareBy<MediaUrlCandidate> {
                when {
                    targetHost != CdnHostRepository.DEFAULT_HOST && it.url.hostEquals(targetHost) -> 0
                    targetHost != CdnHostRepository.DEFAULT_HOST && it.url.isAkamaiHost() -> 1
                    it.url.isTfAllHost() -> 4
                    it.url.isCosovHost() -> 3
                    else -> 2
                }
            }.thenBy { it.typePriority }
                .thenBy { it.bandwidth.takeIf { bw -> bw > 0 } ?: Int.MAX_VALUE }
        )
        for (candidate in sorted) {
            val testUrl = candidate.url.replaceHostForTarget(targetHost)
            val status = probeMediaUrl(testUrl, referer, userAgent, webHeaders)
            Log.d(
                "SpeedTest probe source=$sourceName raw=${candidate.url.hostForLog()} " +
                        "target=${testUrl.hostForLog()} status=$status " +
                        "kind=${candidate.kind} bandwidth=${candidate.bandwidth} path=${testUrl.pathForLog()}"
            )
            if (status in 200..299) {
                Log.d(
                    "SpeedTest source selected source=$sourceName raw=${candidate.url.hostForLog()} " +
                            "target=${testUrl.hostForLog()} kind=${candidate.kind} bandwidth=${candidate.bandwidth}"
                )
                return candidate.url
            }
        }
        debugLog {
            "SpeedTest no usable candidate source=$sourceName target=$targetHost count=${sorted.size}"
        }
        return null
    }

    private fun probeMediaUrl(
        url: String,
        referer: String,
        userAgent: String,
        webHeaders: Boolean
    ): Int {
        val headStatus = probeMediaUrl(url, referer, userAgent, webHeaders, "HEAD")
        if (headStatus in 200..299) return headStatus
        val getStatus = probeMediaUrl(url, referer, userAgent, webHeaders, "GET")
        return if (getStatus in 200..299) getStatus else headStatus
    }

    private fun probeMediaUrl(
        url: String,
        referer: String,
        userAgent: String,
        webHeaders: Boolean,
        method: String
    ): Int {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                if (method == "GET") {
                    setRequestProperty("Range", "bytes=0-0")
                }
                setRequestProperty("Connection", "close")
                if (webHeaders) {
                    setRequestProperty("Referer", referer)
                    setRequestProperty("Origin", "https://www.bilibili.com")
                    applyCookieHeader(url, referer)
                }
            }
            connection.connect()
            val status = connection.responseCode
            if (method == "GET" && status in 200..299) {
                runCatching { connection.inputStream.read(ByteArray(1)) }
            }
            Log.d("SpeedTest probe $method target=${url.hostForLog()} status=$status")
            status
        } catch (e: Throwable) {
            debugLog {
                "SpeedTest probe $method failed target=${url.hostForLog()} error=${e.javaClass.simpleName}: ${e.message}"
            }
            -1
        } finally {
            connection?.disconnect()
        }
    }

    private fun collectWebPlayUrlCandidates(json: JSONObject): List<MediaUrlCandidate> {
        val dash = json.optJSONObject("data")?.optJSONObject("dash") ?: return emptyList()
        val candidates = mutableListOf<MediaUrlCandidate>()
        dash.optJSONArray("video")?.asSequence<JSONObject>()?.forEach { item ->
            addJsonMediaCandidates(candidates, item, 0, "video")
        }
        dash.optJSONArray("audio")?.asSequence<JSONObject>()?.forEach { item ->
            addJsonMediaCandidates(candidates, item, 1, "audio")
        }
        return candidates.distinctBy { it.url }
    }

    private fun addJsonMediaCandidates(
        candidates: MutableList<MediaUrlCandidate>,
        item: JSONObject,
        typePriority: Int,
        kind: String
    ) {
        val bandwidth = item.optInt("bandwidth", 0)
        sequenceOf("baseUrl", "base_url")
            .map { item.optString(it) }
            .filter { it.isNotBlank() }
            .forEach { candidates += MediaUrlCandidate(it, typePriority, bandwidth, kind) }
        sequenceOf("backupUrl", "backup_url")
            .mapNotNull { item.optJSONArray(it) }
            .flatMap { it.asSequence<String>() }
            .filter { it.isNotBlank() }
            .forEach { candidates += MediaUrlCandidate(it, typePriority, bandwidth, kind) }
    }

    private fun collectMediaCandidates(playViewReply: UGCPlayViewReply): List<MediaUrlCandidate> {
        val info = playViewReply.videoInfo
        val candidates = mutableListOf<MediaUrlCandidate>()
        info.streamListList.map { it.dashVideo }.forEach { video ->
            video.baseUrl.takeIf { it.isNotBlank() }?.let {
                candidates += MediaUrlCandidate(it, 0, video.bandwidth, "video")
            }
            video.backupUrlList.filter { it.isNotBlank() }.forEach {
                candidates += MediaUrlCandidate(it, 0, video.bandwidth, "video")
            }
        }
        info.dashAudioList.forEach { audio ->
            audio.baseUrl.takeIf { it.isNotBlank() }?.let {
                candidates += MediaUrlCandidate(it, 1, audio.bandwidth, "audio")
            }
            audio.backupUrlList.filter { it.isNotBlank() }.forEach {
                candidates += MediaUrlCandidate(it, 1, audio.bandwidth, "audio")
            }
        }
        return candidates.distinctBy { it.url }
    }

    private fun String.isTfAllHost(): Boolean =
        runCatchingOrNull { Uri.parse(this).encodedAuthority }
            ?.contains("upos-tf-all", ignoreCase = true) == true

    private fun String.isAkamaiHost(): Boolean =
        runCatchingOrNull { Uri.parse(this).encodedAuthority }
            ?.contains("akamaized.net", ignoreCase = true) == true

    private fun String.isCosovHost(): Boolean =
        runCatchingOrNull { Uri.parse(this).encodedAuthority }
            ?.contains("cosov", ignoreCase = true) == true

    private fun String.hostEquals(host: String): Boolean =
        runCatchingOrNull { Uri.parse(this).encodedAuthority }
            ?.equals(host, ignoreCase = true) == true

    private fun String.replaceHostForTarget(targetHost: String): String =
        if (targetHost == CdnHostRepository.DEFAULT_HOST) this
        else Uri.parse(this).buildUpon().authority(targetHost).build().toString()

    private fun String.pathForLog(): String =
        runCatchingOrNull { Uri.parse(this).encodedPath }
            ?.takeLast(96)
            ?: ""

    private fun StringBuilder.compactForLog(): String =
        toString().replace(Regex("""\s+"""), " ").take(160)

    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(message())
    }

    private fun HttpURLConnection.applyCookieHeader(url: String, referer: String): Int {
        val cookie = cookieHeader(url, referer) ?: return 0
        setRequestProperty("Cookie", cookie)
        return cookie.cookieCount()
    }

    private fun cookieHeader(url: String, referer: String): String? {
        val manager = runCatchingOrNull { CookieManager.getInstance() } ?: return null
        val cookies = sequenceOf(
            "https://www.bilibili.com",
            referer,
            url
        )
            .mapNotNull { runCatchingOrNull { manager.getCookie(it) } }
            .flatMap { it.splitToSequence(";") }
            .map { it.trim() }
            .filter { it.contains("=") }
            .distinctBy { it.substringBefore("=") }
            .toList()
        return cookies.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun String.cookieCount(): Int =
        split(";").count { it.trim().contains("=") }

    private val browserUserAgent: String
        get() = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

    private val appUserAgent: String
        get() = "Mozilla/5.0 BiliDroid/7.18.0 (bbcallen@gmail.com) " +
                "os/android model/${Build.MODEL} mobi_app/android build/7180300 " +
                "channel/bili innerVer/7180310 osVer/${Build.VERSION.RELEASE} network/2"

    companion object {
        private const val TEST_WINDOW_MS = 5000L
        private const val MAX_TEST_BYTES = 5L * 1024L * 1024L
        private const val ERROR_PREVIEW_LIMIT = 256
    }
}
