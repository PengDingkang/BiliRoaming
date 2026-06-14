package me.iacn.biliroaming.hook

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.RecentVideoStore
import me.iacn.biliroaming.utils.RecentVideoStore.videoBvid
import me.iacn.biliroaming.utils.av2bv
import me.iacn.biliroaming.utils.callMethodOrNull
import me.iacn.biliroaming.utils.callMethodOrNullAs
import me.iacn.biliroaming.utils.firstExistingMethodName
import me.iacn.biliroaming.utils.hookAfterMethod
import me.iacn.biliroaming.utils.hookBeforeMethod
import me.iacn.biliroaming.utils.mossResponseHandlerProxy

class RecentVideoHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val videoDetailActivityNames = setOf(
        "com.bilibili.video.videodetail.VideoDetailsActivity",
        "tv.danmaku.bili.ui.video.VideoDetailsActivity",
        "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity",
        "com.bilibili.ship.theseus.all.UnitedBizDetailsActivity",
    )

    override fun startHook() {
        Log.d("startHook: RecentVideoHook")
        hookVideoDetailIntent()
        hookPlayViewUnite()
    }

    private fun hookVideoDetailIntent() {
        Activity::class.java.hookAfterMethod("onCreate", Bundle::class.java) { param ->
            val activity = param.thisObject as Activity
            if (activity.javaClass.name in videoDetailActivityNames) {
                recordFromIntent(activity.intent)
            }
        }
        Activity::class.java.hookAfterMethod("onResume") { param ->
            val activity = param.thisObject as Activity
            if (activity.javaClass.name in videoDetailActivityNames) {
                recordFromIntent(activity.intent)
            }
        }
        Activity::class.java.hookAfterMethod("onNewIntent", Intent::class.java) { param ->
            val activity = param.thisObject as Activity
            if (activity.javaClass.name in videoDetailActivityNames) {
                recordFromIntent(param.args[0] as? Intent)
            }
        }
    }

    private fun hookPlayViewUnite() {
        instance.playerMossClass?.apply {
            val playViewUniteReqClass = instance.playViewUniteReqClass ?: return@apply
            firstExistingMethodName("executePlayViewUnite")?.let { methodName ->
                hookBeforeMethod(methodName, playViewUniteReqClass) { param ->
                    recordFromRequest(param.args[0], "playViewUniteReq")
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
                    recordFromRequest(param.args[0], "playViewUniteAsyncReq")
                    param.args[1] = param.args[1].mossResponseHandlerProxy { reply ->
                        recordFromReply(reply)
                    }
                }
            }
        }
    }

    private fun recordFromIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val bvid = data.videoBvid() ?: return
        val cid = RecentVideoStore.parsePositiveLong(data.getQueryParameter("cid")) ?: return
        RecentVideoStore.saveFromBvid(bvid, cid, "videoIntent")
    }

    private fun recordFromRequest(req: Any?, source: String) {
        req ?: return
        val vod = req.callMethodOrNull("getVod") ?: return
        val aid = RecentVideoStore.parsePositiveLong(vod.callMethodOrNull("getAid")) ?: -1L
        val cid = RecentVideoStore.parsePositiveLong(vod.callMethodOrNull("getCid")) ?: -1L
        val bvid = req.callMethodOrNull("getBvid")?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: aid.takeIf { it > 0 }?.let { av2bv(it) }
            ?: return
        RecentVideoStore.save(aid, bvid, cid, source)
    }

    private fun recordFromReply(reply: Any?) {
        reply ?: return
        val playArc = reply.callMethodOrNull("getPlayArc") ?: return
        val aid = RecentVideoStore.parsePositiveLong(playArc.callMethodOrNull("getAid")) ?: -1L
        val cid = RecentVideoStore.parsePositiveLong(playArc.callMethodOrNull("getCid")) ?: -1L
        val bvid = aid.takeIf { it > 0 }?.let { av2bv(it) } ?: return
        RecentVideoStore.save(aid, bvid, cid, "playViewUniteReply")
    }
}
