package me.iacn.biliroaming.utils

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.future.future
import me.iacn.biliroaming.BuildConfig
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.UGCPlayViewReply
import me.iacn.biliroaming.XposedInit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

object UposReplaceHelper {
    private const val LOG_PREFIX = "BiliRoamingUPOS"

    private val hwHost = CdnHostRepository.HW_HOST
    private val aliovHost = CdnHostRepository.ALIOV_HOST

    val isLocatedCn by lazy {
        (runCatchingOrNull { XposedInit.country.get(5L, TimeUnit.SECONDS) } ?: "cn") == "cn"
    }

    val forceUpos = sPrefs.getBoolean("force_upos", false)
    val enablePcdnBlock = sPrefs.getBoolean("block_pcdn", false)
    val enableLivePcdnBlock = sPrefs.getBoolean("block_pcdn_live", false)

    private lateinit var videoUposList: CompletableFuture<List<String>>
    private val mainVideoUpos =
        sPrefs.getString("upos_host", null) ?: if (isLocatedCn) hwHost else aliovHost
    private val extraVideoUposList = CdnHostRepository.backupHostsFor(mainVideoUpos, isLocatedCn)
    private val videoUposBase by lazy {
        runCatchingOrNull { videoUposList.get(500L, TimeUnit.MILLISECONDS) }?.get(0)
            ?: mainVideoUpos
    }
    val videoUposBackups by lazy {
        runCatchingOrNull {
            videoUposList.get(500L, TimeUnit.MILLISECONDS).drop(1).take(2)
                .takeIf { it.size >= 2 }
        }
            ?: extraVideoUposList
    }
    const val liveUpos = "c1--cn-gotcha01.bilivideo.com"

    val enableUposReplace = (mainVideoUpos != "\$1")

    private val overseaVideoUposRegex by lazy {
        Regex("""(akamai|(ali|hw|cos)\w*ov|hk-eq|bstar|--ov-gotcha)""", RegexOption.IGNORE_CASE)
    }
    private val urlBwRegex by lazy { Regex("""(bw=[^&]*)""") }
    private val ipPCdnRegex by lazy { Regex("""^https?://\d{1,3}\.\d{1,3}""") }
    private val pcdnHostRegex by lazy {
        Regex("""(szbdyd\.com|\.mcdn\.bilivideo|\.mcdn\.biliapi|pcdn)""", RegexOption.IGNORE_CASE)
    }
    val gotchaRegex by lazy { Regex("""https?://[\w-]*--[\w-]*-gotcha[\w-]*\.bilivideo""") }
    private val skipTfOverride = ThreadLocal<Boolean>()

    fun logUposDebug(message: () -> String) {
        if (BuildConfig.DEBUG) {
            Log.d("$LOG_PREFIX: ${message()}")
        }
    }

    fun debugConfigSummary(): String =
        "enabled=$enableUposReplace main=$mainVideoUpos locatedCn=$isLocatedCn " +
                "force=$forceUpos blockPcdn=$enablePcdnBlock blockLivePcdn=$enableLivePcdnBlock " +
                "fallbacks=${extraVideoUposList.joinToString()}"

    fun String.hostForLog(): String =
        runCatchingOrNull { Uri.parse(this).encodedAuthority }
            ?.takeIf { it.isNotBlank() }
            ?: take(120)

    fun Collection<String>.hostsForLog(): String =
        joinToString(prefix = "[", postfix = "]") { it.hostForLog() }

    fun String.videoReplaceReason(): String = when {
        isLocalPlaybackUrl() -> "skip_local_playback"
        contains(".mcdn.bilivideo", ignoreCase = true) -> "skip_mcdn_bilivideo"
        contains(".mcdn.biliapi", ignoreCase = true) -> "skip_mcdn_biliapi"
        contains(ipPCdnRegex) -> "skip_ip_pcdn"
        forceUpos && startsWith("http") -> "force_upos"
        enablePcdnBlock && isPCdnUpos() -> "block_pcdn"
        isOverseaUpos() -> "oversea"
        else -> "not_needed"
    }

    fun initVideoUposList(mClassLoader: ClassLoader) {
        logUposDebug { "initVideoUposList ${debugConfigSummary()}" }
        videoUposList = MainScope().future(Dispatchers.IO) {
            val bCacheRegex = Regex("""cn-.*\.bilivideo""")
            mutableListOf(mainVideoUpos).apply {
                // 8K video sample, without area limitation, reply probably contains Mirror CDN
                val playViewReply = instance.playViewReqClass?.new()?.apply {
                    callMethod("setAid", 355749246L)
                    callMethod("setCid", 1115447032L)
                    callMethod("setQn", 127)
                    callMethod("setFnval", 4048)
                    callMethod("setFourk", true)
                    callMethod("setForceHost", 2)
                }?.let { playViewReqUgc ->
                    instance.playURLMossClass?.new()?.callMethod("playView", playViewReqUgc)
                        ?.callMethodAs<ByteArray>("toByteArray")?.let {
                            UGCPlayViewReply.parseFrom(it)
                        }
                }
                val officialList = playViewReply?.videoInfo?.let { info ->
                    mutableListOf<String>().apply {
                        info.streamListList?.forEach { stream ->
                            add(stream.dashVideo.baseUrl)
                            addAll(stream.dashVideo.backupUrlList)
                        }
                        info.dashAudioList?.forEach { dashItem ->
                            add(dashItem.baseUrl)
                            addAll(dashItem.backupUrlList)
                        }
                    }
                }?.mapNotNull { Uri.parse(it).encodedAuthority }?.distinct()
                    ?.filter { !it.isPCdnUpos() }.orEmpty()
                logUposDebug {
                    "official candidates count=${officialList.size} hosts=${officialList.take(8)}"
                }
                addAll(officialList.filter { !(it.contains(bCacheRegex) || it == mainVideoUpos) }
                    .ifEmpty { officialList })
                addAll(extraVideoUposList)
            }.also { hosts ->
                logUposDebug { "videoUposList resolved count=${hosts.size} hosts=${hosts.take(10)}" }
                hookTf(mClassLoader)
            }
        }
    }

    fun String.isPCdnUpos() =
        contains(pcdnHostRegex) || contains(ipPCdnRegex)

    fun String.isOverseaUpos() = isLocatedCn == contains(overseaVideoUposRegex)

    fun String.isNeedReplaceVideoUpos() =
        if (isLocalPlaybackUrl() ||
            contains(".mcdn.bilivideo", ignoreCase = true) ||
            contains(".mcdn.biliapi", ignoreCase = true) ||
            contains(ipPCdnRegex)
        ) {
            // IP:Port type PCDN currently only exists in Live and Thai Video.
            // Cannot simply replace IP:Port or 'mcdn.bilivideo' like PCDN's host
            false
        } else {
            // only 'szbdyd.com' like PCDN can be replace
            (forceUpos && startsWith("http")) ||
                    (enablePcdnBlock && isPCdnUpos()) ||
                    isOverseaUpos()
        }

    fun String.isLocalPlaybackUrl(): Boolean {
        if (!startsWith("http", ignoreCase = true)) return false
        val host = runCatchingOrNull { Uri.parse(this).host?.lowercase() } ?: return false
        return host == "localhost" ||
                host == "0.0.0.0" ||
                host == "::1" ||
                host.startsWith("127.")
    }

    fun String.replaceUpos(
        upos: String = videoUposBase, needReplace: Boolean = true
    ): String {
        fun String.replaceUposBw(): String = replace(urlBwRegex, "bw=1280000")

        return if (needReplace) {
            val uri = Uri.parse(this)
            val newUpos = uri.getQueryParameter("xy_usource") ?: upos
            uri.replaceUpos(newUpos).toString().replaceUposBw().also { result ->
                logUposDebug {
                    "replaceUpos need=true from=${hostForLog()} target=$newUpos result=${result.hostForLog()}"
                }
            }
        } else replaceUposBw().also { result ->
            logUposDebug {
                "replaceUpos need=false from=${hostForLog()} result=${result.hostForLog()}"
            }
        }
    }

    fun Uri.replaceUpos(upos: String): Uri = buildUpon().authority(upos).build()

    fun <T> withoutTfOverride(block: () -> T): T {
        skipTfOverride.set(true)
        return try {
            block()
        } finally {
            skipTfOverride.remove()
        }
    }

    private fun hookTf(mClassLoader: ClassLoader) {
        if (!(enablePcdnBlock || forceUpos)) return
        // fake grpc TF header then only reply with mirror type playurl
        "com.bilibili.lib.moss.utils.RuntimeHelper".from(mClassLoader)
            ?.hookAfterMethod("tf") { param ->
                if (skipTfOverride.get() == true) return@hookAfterMethod
                val result = param.result
                if (result.callMethodOrNullAs<Int>("getNumber") != 0) return@hookAfterMethod
                result.javaClass.callStaticMethodOrNull("forNumber", 1)?.let {
                    logUposDebug { "tf header overridden to mirror mode" }
                    param.result = it
                }
            }
    }

}
