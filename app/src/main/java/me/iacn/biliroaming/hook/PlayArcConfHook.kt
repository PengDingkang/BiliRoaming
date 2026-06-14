package me.iacn.biliroaming.hook

import android.net.Uri
import me.iacn.biliroaming.*
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.*
import kotlin.math.ceil

class PlayArcConfHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val playURLMoss get() = instance.playURLMossClass?.new()
    private val viewMoss get() = instance.viewMossClass?.new()

    override fun startHook() {
        if (!sPrefs.getBoolean("play_arc_conf", false)) return

        val playURLMossClass = instance.playURLMossClass
        val playViewReqClass = instance.playViewReqClass
        val playViewMethod = playURLMossClass?.firstExistingMethodName("executePlayView", "playView")
        if (playURLMossClass != null && playViewReqClass != null && playViewMethod != null) {
            playURLMossClass.hookAfterMethod(playViewMethod, playViewReqClass) { param ->
                param.result?.callMethod("getPlayArc")?.run {
                    arrayOf(
                        callMethod("getCastConf"),
                        callMethod("getBackgroundPlayConf"),
                        callMethod("getSmallWindowConf")
                    ).forEach {
                        it?.callMethod("setDisabled", false)
                        it?.callMethod("setIsSupport", true)
                    }
                }
            }
        }
        val supportedArcConf = "com.bapis.bilibili.playershared.ArcConf"
            .from(mClassLoader)?.new()?.apply {
                callMethod("setDisabled", false)
                callMethod("setIsSupport", true)
            }
        val arcConfs = "com.bapis.bilibili.playershared.PlayArcConf"
                .from(mClassLoader)?.callStaticMethod("getDefaultInstance")
        arcConfs?.callMethodAs<LinkedHashMap<Int, Any?>>("internalGetMutableArcConfs")
                ?.run {
                    // CASTCONF,BACKGROUNDPLAY,SMALLWINDOW,LISTEN
                    intArrayOf(2, 9, 23, 36).forEach { this[it] = supportedArcConf }
                }

        val playerMossClass = instance.playerMossClass
        val playViewUniteReqClass = instance.playViewUniteReqClass
        val playViewUniteMethod =
            playerMossClass?.firstExistingMethodName("executePlayViewUnite", "playViewUnite")
        if (playerMossClass != null && playViewUniteReqClass != null && playViewUniteMethod != null) {
            playerMossClass.hookAfterMethod(playViewUniteMethod, playViewUniteReqClass) { param ->
                param.result?.callMethod("mergePlayArcConf", arcConfs)
            }
        }
        val mossResponseHandlerClass = instance.mossResponseHandlerClass
        if (playerMossClass != null && playViewUniteReqClass != null &&
            mossResponseHandlerClass != null &&
            playerMossClass.firstExistingMethodName("playViewUnite") != null
        ) {
            playerMossClass.hookBeforeMethod(
                "playViewUnite",
                playViewUniteReqClass,
                mossResponseHandlerClass
            ) { param ->
                param.args[1] = param.args[1].mossResponseHandlerProxy { resp ->
                    resp?.callMethod("mergePlayArcConf", arcConfs)
                }
            }
        }
        "com.bapis.bilibili.app.listener.v1.ListenerMoss".from(mClassLoader)?.run {
            val playlistHook = { param: MethodHookParam ->
                val req = param.args[0]
                param.args[1] = param.args[1].mossResponseHandlerProxy { resp ->
                    runCatching {
                        reconstructPlaylistResponse(req, resp)
                    }.onFailure {
                        Log.e(it)
                        Log.toast("听视频解锁失败")
                    }
                }
            }
            val handlerClass = instance.mossResponseHandlerClass
            if (handlerClass != null) {
                "com.bapis.bilibili.app.listener.v1.PlaylistReq".from(mClassLoader)
                    ?.takeIf { firstExistingMethodName("playlist") != null }
                    ?.let { reqClass ->
                        hookBeforeMethod("playlist", reqClass, handlerClass, hooker = playlistHook)
                    }
                "com.bapis.bilibili.app.listener.v1.RcmdPlaylistReq".from(mClassLoader)
                    ?.takeIf { firstExistingMethodName("rcmdPlaylist") != null }
                    ?.let { reqClass ->
                        hookBeforeMethod("rcmdPlaylist", reqClass, handlerClass, hooker = playlistHook)
                    }
                "com.bapis.bilibili.app.listener.v1.PlayHistoryReq".from(mClassLoader)
                    ?.takeIf { firstExistingMethodName("playHistory") != null }
                    ?.let { reqClass ->
                        hookBeforeMethod("playHistory", reqClass, handlerClass, hooker = playlistHook)
                    }
            }
            "com.bapis.bilibili.app.listener.v1.PlayURLReq".from(mClassLoader)
                ?.let { reqClass ->
                    val playURLMethod = firstExistingMethodName("executePlayURL", "playURL")
                        ?: return@let
                    hookAfterMethod(playURLMethod, reqClass) { param ->
                        if (instance.networkExceptionClass?.isInstance(param.throwable) == true)
                            return@hookAfterMethod
                        val resp = param.result ?: "com.bapis.bilibili.app.listener.v1.PlayURLResp"
                            .on(mClassLoader).new()
                        val playable = resp.callMethodAs<Int>("getPlayable")
                        val playerInfoMap = resp.callMethodAs<Map<*, *>>("getPlayerInfoMap")
                        if (playable == 0 && playerInfoMap.isNotEmpty())
                            return@hookAfterMethod
                        Log.toast("听视频解锁中")
                        param.result = reconstructPlayUrlResponse(param.args[0], resp)
                    }
            }
        }
    }

    private fun reconstructPlaylistResponse(req: Any, resp: Any?) {
        resp ?: return
        val needPartItems = mutableListOf<Any>()
        resp.callMethodAs<List<Any>>("getListList").filter {
            it.callMethodAs<Int>("getPlayable") != 0
        }.forEach {
            it.callMethod("setPlayable", 0)
            if (it.callMethodAs<Int>("getPartsCount") <= 0)
                needPartItems.add(it)
        }
        if (needPartItems.isEmpty())
            return
        val playerArgs = runCatchingOrNull {
            PlayerArgs.parseFrom(
                req.callMethod("getPlayerArgs")
                    ?.callMethodAs<ByteArray>("toByteArray")
            )
        } ?: playerArgs {
            fnval = BangumiPlayUrlHook.MAX_FNVAL.toLong()
            forceHost = 2
            qn = 64
        }
        val commViewReq = viewReq {
            fnval = playerArgs.fnval.toInt()
            forceHost = playerArgs.forceHost.toInt()
            fourk = 1
            this.playerArgs = playerArgs
            qn = playerArgs.qn.toInt()
        }.toByteArray().let {
            instance.viewReqClass?.callStaticMethod("parseFrom", it)
        } ?: return
        val bkArcPartClass = instance.bkArcPartClass ?: return
        for (item in needPartItems) {
            val oid = item.callMethod("getArc")?.callMethodAs<Long>("getOid")
                ?: continue
            val viewReq = commViewReq.apply { callMethod("setAid", oid) }
            val viewReply = ViewReply.parseFrom(
                viewMoss?.callMethod("view", viewReq)
                    ?.callMethodAs<ByteArray>("toByteArray") ?: continue
            )
            val parts = viewReply.pagesList.mapNotNull { p ->
                bKArcPart {
                    duration = p.page.duration
                    this.oid = oid
                    page = p.page.page
                    subId = p.page.cid
                    title = p.page.part
                }.toByteArray().let {
                    bkArcPartClass.callStaticMethod("parseFrom", it)
                }
            }
            item.callMethod("addAllParts", parts)
        }
    }

    private fun reconstructPlayUrlResponse(req: Any, resp: Any) = runCatching {
        val reqObj = ListenPlayURLReq.parseFrom(req.callMethodAs<ByteArray>("toByteArray"))
        val commPlayViewReq = playViewReq {
            epId = reqObj.item.oid
            qn = reqObj.playerArgs.qn
            fnval = reqObj.playerArgs.fnval.toInt()
            forceHost = reqObj.playerArgs.forceHost.toInt()
            fourk = true
            preferCodecType = CodeType.CODE265
        }.toByteArray().let {
            instance.playViewReqClass?.callStaticMethod("parseFrom", it)
        } ?: return@runCatching resp
        reqObj.item.subIdList.associateWith { subId ->
            val playViewReq = commPlayViewReq.apply { callMethod("setCid", subId) }
            val playViewReply = UGCPlayViewReply.parseFrom(
                playURLMoss?.callMethod("playView", playViewReq)
                    ?.callMethodAs<ByteArray>("toByteArray") ?: return@associateWith null
            )
            listenPlayInfo {
                var deadline = 0L
                fnval = reqObj.playerArgs.fnval.toInt()
                format = playViewReply.videoInfo.format
                length = playViewReply.videoInfo.timelength
                qn = playViewReply.videoInfo.quality
                videoCodecid = playViewReply.videoInfo.videoCodecid
                playDash = listenPlayDASH {
                    playViewReply.videoInfo.dashAudioList.map {
                        listenDashItem {
                            id = it.id
                            size = it.size
                            bandwidth = it.bandwidth
                            baseUrl = it.baseUrl
                            backupUrl.addAll(it.backupUrlList)
                            if (deadline == 0L)
                                deadline = Uri.parse(baseUrl).getQueryParameter("deadline")
                                    ?.toLongOrNull() ?: 0
                        }
                    }.let { audio.addAll(it) }
                    duration = ceil(playViewReply.videoInfo.timelength / 1000.0).toInt()
                    minBufferTime = 0.0F
                }
                playViewReply.videoInfo.streamListList.map {
                    formatDescription {
                        it.streamInfo.let { si ->
                            description = si.description
                            displayDesc = si.displayDesc
                            format = si.format
                            quality = si.quality
                            superscript = si.superscript
                        }
                    }
                }.let { formats.addAll(it) }
                expireTime = deadline
            }
        }.mapNotNull { it.value?.let { v -> it.key to v } }.toMap()
            .takeIf { it.isNotEmpty() }?.let {
                listenPlayURLResp {
                    item = reqObj.item
                    playable = 0
                    playerInfo.putAll(it)
                }.let {
                    resp.javaClass.callStaticMethod("parseFrom", it.toByteArray())
                }
            } ?: resp
    }.onFailure {
        Log.e(it)
        Log.toast("听视频解锁失败")
    }.getOrDefault(resp)
}
