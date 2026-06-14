package me.iacn.biliroaming.hook

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.BilibiliSponsorBlock
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.RecentVideoStore
import me.iacn.biliroaming.utils.av2bv
import me.iacn.biliroaming.utils.callMethod
import me.iacn.biliroaming.utils.callMethodAs
import me.iacn.biliroaming.utils.firstExistingMethodName
import me.iacn.biliroaming.utils.hookAfterMethod
import me.iacn.biliroaming.utils.hookBeforeMethod
import me.iacn.biliroaming.utils.mossResponseHandlerReplaceProxy
import me.iacn.biliroaming.utils.sPrefs
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

class SkipVideoAd(classLoader: ClassLoader) : BaseHook(classLoader) {

    private var lastSeekTime = 0L
    private var playerRef: WeakReference<Any>? = null
    private val player get() = playerRef?.get()
    private var duration: Int = -1
    @Volatile
    private var segments: List<BilibiliSponsorBlock.Segment>? = null
    private var bvid: String = ""
    private var cid: String = ""
    private var loadingVideoKey: String? = null
    private var waitTime = 1000
    private val videoDetailActivityNames = setOf(
        "com.bilibili.video.videodetail.VideoDetailsActivity",
        "tv.danmaku.bili.ui.video.VideoDetailsActivity",
        "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity",
        "com.bilibili.ship.theseus.all.UnitedBizDetailsActivity",
    )

    override fun startHook() {
        if (!sPrefs.getBoolean("skip_video_ad", false)) return

        Log.d("startHook: SkipVideoAd")

        hookVideoDetailIntent()

        instance.playerMossClass?.apply {
            val playViewUniteReqClass = instance.playViewUniteReqClass ?: return@apply
            firstExistingMethodName("executePlayViewUnite")?.let { methodName ->
                hookBeforeMethod(methodName, playViewUniteReqClass) { param ->
                    val req = param.args[0]
                    var newBvid = req.callMethodAs<String>("getBvid")
                    val vod = req.callMethod("getVod") ?: return@hookBeforeMethod
                    if (newBvid.isEmpty()) {
                        val aid = vod.callMethodAs<Long>("getAid")
                        if (aid == -1L) {
                            return@hookBeforeMethod
                        }
                        newBvid = av2bv(aid)
                    }
                    updateVideoIdentity(newBvid, vod.callMethodAs<Long>("getCid").toString())
                }
            }

            val mossResponseHandlerClass = instance.mossResponseHandlerClass
            if (mossResponseHandlerClass != null &&
                firstExistingMethodName("playViewUnite") != null
            ) {
                hookBeforeMethod(
                    "playViewUnite",
                    playViewUniteReqClass,
                    mossResponseHandlerClass
                ) { param ->
                    param.args[1] = param.args[1].mossResponseHandlerReplaceProxy { reply ->
                        reply ?: return@mossResponseHandlerReplaceProxy null
                        val playArc =
                            reply.callMethod("getPlayArc")
                                ?: return@mossResponseHandlerReplaceProxy null
                        cid = playArc.callMethodAs<Long>("getCid").toString()
                        val aid = playArc.callMethodAs<Long>("getAid") ?: -1L
                        if (aid == -1L) {
                            return@mossResponseHandlerReplaceProxy null
                        }
                        updateVideoIdentity(av2bv(aid), cid)
                        null
                    }
                }
            }
        }

        instance.playerCoreServiceV2Class?.apply {
            hookAfterMethod("getCurrentPosition") { param ->
                playerRef = WeakReference(param.thisObject)
                if (duration <= 0) {
                    duration = player?.callMethodAs<Int>("getDuration") ?: -1
                }
                loadSegmentsIfNeeded()

                val now = System.currentTimeMillis()
                if (now - lastSeekTime > waitTime) {
                    lastSeekTime = now
                    waitTime = if(seekTo(param.result as Int)) 3000 else 1000
                }
            }
        }
    }

    private fun hookVideoDetailIntent() {
        Activity::class.java.hookAfterMethod("onCreate", Bundle::class.java) { param ->
            val activity = param.thisObject as Activity
            if (activity.javaClass.name in videoDetailActivityNames) {
                updateVideoIdentityFromIntent(activity.intent)
            }
        }
        Activity::class.java.hookAfterMethod("onResume") { param ->
            val activity = param.thisObject as Activity
            if (activity.javaClass.name in videoDetailActivityNames) {
                updateVideoIdentityFromIntent(activity.intent)
            }
        }
        Activity::class.java.hookAfterMethod("onNewIntent", Intent::class.java) { param ->
            val activity = param.thisObject as Activity
            if (activity.javaClass.name in videoDetailActivityNames) {
                updateVideoIdentityFromIntent(param.args[0] as? Intent)
            }
        }
    }

    private fun updateVideoIdentityFromIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val newBvid = data.videoBvid() ?: return
        val newCid = data.getQueryParameter("cid")?.takeIf { it.isNotBlank() } ?: return
        updateVideoIdentity(newBvid, newCid)
    }

    private fun Uri.videoBvid(): String? {
        getQueryParameter("bvid")?.takeIf { it.isNotBlank() }?.let { return it }
        val rawId = lastPathSegment?.takeIf { it.isNotBlank() } ?: return null
        if (rawId.startsWith("BV", ignoreCase = true)) return rawId
        return rawId.toLongOrNull()?.let { av2bv(it) }
    }

    private fun updateVideoIdentity(newBvid: String, newCid: String) {
        val normalizedCid = RecentVideoStore.parsePositiveLong(newCid)?.toString() ?: return
        if (newBvid.isBlank()) return
        val newVideoKey = "$newBvid:$normalizedCid"
        if (newVideoKey == videoKey()) return
        bvid = newBvid
        cid = normalizedCid
        duration = -1
        segments = null
        loadingVideoKey = null
        waitTime = 1000
        Log.d("SkipVideoAd: video changed bvid=$bvid cid=$cid")
        RecentVideoStore.parsePositiveLong(cid)?.let {
            RecentVideoStore.saveFromBvid(bvid, it, "skipVideoAd")
        }
        loadSegmentsIfNeeded()
    }

    private fun loadSegmentsIfNeeded() {
        val key = videoKey() ?: return
        if (segments != null || loadingVideoKey == key) return
        val requestBvid = bvid
        val requestCid = cid
        loadingVideoKey = key
        CoroutineScope(Dispatchers.IO).launch {
            var retryCount = 0
            val maxRetries = 3
            var loadedSegments: List<BilibiliSponsorBlock.Segment>? = null
            while (retryCount < maxRetries) {
                loadedSegments = BilibiliSponsorBlock(requestBvid, requestCid).getSegments()
                if (loadedSegments.isNullOrEmpty()) {
                    retryCount++
                    delay(1000)
                } else {
                    break
                }
            }
            if (videoKey() != key) return@launch
            segments = loadedSegments
            Log.d("SkipVideoAd: loaded ${loadedSegments.orEmpty().size} segments for $key")
            loadedSegments?.takeIf { it.isNotEmpty() }?.let { showLoadedSegmentsToast(it) }
        }
    }

    private fun videoKey(): String? =
        "$bvid:$cid".takeIf { bvid.isNotBlank() && cid.isNotBlank() && cid != "0" }

    private fun showLoadedSegmentsToast(loadedSegments: List<BilibiliSponsorBlock.Segment>) {
        val ranges = loadedSegments.take(3).joinToString("、") { segment ->
            "${formatTime(segment.segment[0])}~${formatTime(segment.segment[1])}"
        }
        val message = if (loadedSegments.size == 1) {
            "将跳过 $ranges 广告内容"
        } else {
            "将跳过 ${loadedSegments.size} 段广告内容：$ranges${if (loadedSegments.size > 3) " 等" else ""}"
        }
        Log.toast(message)
    }

    private fun formatTime(seconds: Float): String {
        val totalSeconds = seconds.roundToInt().coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val secs = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%02d:%02d".format(minutes, secs)
        }
    }

    private fun seekTo(position: Int?): Boolean {
        if (position != null) {
            if (position > duration) return  false
        }

        if (segments != null) {
            for (segment in segments) {
                val start = (segment.segment[0]*1000).toInt()
                val end = (segment.segment[1]*1000).toInt()
                if (position in start..<end) {
                    Log.toast("已跳过广告片段")
                    player?.callMethod(instance.seekTo() ?: "seekTo", end)
                    return true
                }
            }
        }
        return false
    }
}
