package me.iacn.biliroaming.hook

import android.os.Bundle
import me.iacn.biliroaming.Constant
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.HookStatus
import me.iacn.biliroaming.utils.callMethodOrNull
import me.iacn.biliroaming.utils.callMethodOrNullAs
import me.iacn.biliroaming.utils.findClassOrNull
import me.iacn.biliroaming.utils.getIntFieldOrNull
import me.iacn.biliroaming.utils.getObjectFieldOrNull
import me.iacn.biliroaming.utils.hookBeforeMethod
import me.iacn.biliroaming.utils.packageName
import me.iacn.biliroaming.utils.sPrefs
import me.iacn.biliroaming.utils.setObjectField

class HdMultiPageHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    override fun startHook() {
        if (packageName != Constant.HD_PACKAGE_NAME) return
        if (!sPrefs.getBoolean("fix_hd_multi_page", true)) return
        Log.d("startHook: HdMultiPageHook")

        val videoItemClass = "tv.danmaku.biliplayerv2.service.g".findClassOrNull(mClassLoader) ?: return
        val directorClass = "tv.danmaku.biliplayerimpl.videodirector.VideosPlayDirectorService"
            .findClassOrNull(mClassLoader) ?: return
        val normalSourceClass = "sl2.c".findClassOrNull(mClassLoader)
        val videoDetailPlayerCallbackClass = "com.bilibili.video.videodetail.player.VideoDetailPlayer\$z"
            .findClassOrNull(mClassLoader)

        directorClass.hookBeforeMethod("R1", videoItemClass) { param ->
            val director = param.thisObject
            val item = param.args[0]
            val video = director.getObjectFieldOrNull("c")
            val videoList = director.getObjectFieldOrNull("a")
            if (redirectGlobalIndex(director, item, video, videoList)) {
                param.result = null
            }
        }

        videoDetailPlayerCallbackClass?.hookBeforeMethod("a", Boolean::class.javaPrimitiveType) { param ->
            val index = param.thisObject.getIntFieldOrNull("b")
            val detail = param.thisObject.getObjectFieldOrNull("c")
            if (playNestedPageAsNormalSource(param.thisObject, normalSourceClass, index, detail)) {
                param.result = null
            }
        }
    }

    private fun playNestedPageAsNormalSource(
        callback: Any,
        normalSourceClass: Class<*>?,
        index: Int?,
        detail: Any?,
    ): Boolean {
        return try {
            if (normalSourceClass == null || index == null || detail == null) return false
            val pageCount = (detail.getObjectFieldOrNull("mPageList") as? List<*>)?.size ?: return false
            val ugcSeason = detail.getObjectFieldOrNull("ugcSeason") ?: return false
            val episodeCount = ugcSeason.getIntFieldOrNull("episodeCount") ?: 0
            if (pageCount <= 1 || pageCount <= episodeCount || index !in 0 until pageCount) return false

            val player = callback.getObjectFieldOrNull("a") ?: return false
            val playerFragment = player.getObjectFieldOrNull("j") ?: return false
            val source = normalSourceClass.getDeclaredConstructor().newInstance()
            val bundle = player.callMethodOrNull("B1", null) as? Bundle ?: Bundle()
            source.callMethodOrNull("x2", detail, bundle)

            val scroller = player.getObjectFieldOrNull("f")
            scroller?.callMethodOrNull("J", callback)
            player.setObjectField("x", index)
            playerFragment.callMethodOrNull("lk", source)
            playerFragment.callMethodOrNull("xc")
            playerFragment.callMethodOrNull("n0", 0, index, true)
            true
        } catch (e: Throwable) {
            recordFailure(e, "VideoDetailPlayer#a")
        }
    }

    private fun redirectGlobalIndex(director: Any, item: Any?, video: Any?, videoList: Any?): Boolean {
        return try {
            if (item == null || video == null || videoList == null) return false
            val requestedIndex = item.callMethodOrNullAs<Int>("getIndex") ?: return false
            val currentCount = videoList.callMethodOrNullAs<Int>("A1", video) ?: return false
            if (requestedIndex < currentCount) return false
            val target = findGlobalIndex(videoList, requestedIndex) ?: return false
            if (target.video === video && target.localIndex == requestedIndex) return false
            director.callMethodOrNull("Z", target.videoIndex, target.localIndex)
            true
        } catch (e: Throwable) {
            recordFailure(e, "VideosPlayDirectorService#R1")
        }
    }

    private fun findGlobalIndex(videoList: Any, index: Int): TargetIndex? {
        val videoCount = videoList.callMethodOrNullAs<Int>("h1") ?: return null
        var offset = index
        for (videoIndex in 0 until videoCount) {
            val video = videoList.callMethodOrNullAs<Any>("c1", videoIndex) ?: continue
            val count = videoList.callMethodOrNullAs<Int>("A1", video) ?: 0
            if (offset < count) return TargetIndex(video, videoIndex, offset)
            offset -= count
        }
        return null
    }

    private fun recordFailure(error: Throwable, target: String): Boolean {
        HookStatus.recordFailure(error, target = target, hookName = "HdMultiPageHook")
        Log.e(error)
        return false
    }

    private data class TargetIndex(val video: Any, val videoIndex: Int, val localIndex: Int)
}
