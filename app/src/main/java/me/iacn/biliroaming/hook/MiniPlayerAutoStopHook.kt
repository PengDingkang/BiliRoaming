package me.iacn.biliroaming.hook

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import me.iacn.biliroaming.BiliBiliPackage.Companion.instance
import me.iacn.biliroaming.utils.HookStatus
import me.iacn.biliroaming.utils.Log
import me.iacn.biliroaming.utils.callMethodOrNull
import me.iacn.biliroaming.utils.callMethodOrNullAs
import me.iacn.biliroaming.utils.firstExistingMethodName
import me.iacn.biliroaming.utils.callStaticMethodOrNull
import me.iacn.biliroaming.utils.from
import me.iacn.biliroaming.utils.getObjectFieldOrNull
import me.iacn.biliroaming.utils.hookAfterAllConstructors
import me.iacn.biliroaming.utils.hookAfterMethod
import me.iacn.biliroaming.utils.hookBeforeMethod
import me.iacn.biliroaming.utils.sPrefs
import me.iacn.biliroaming.utils.setBooleanField
import me.iacn.biliroaming.utils.setObjectField
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MiniPlayerAutoStopHook(classLoader: ClassLoader) : BaseHook(classLoader) {
    private val completedAutoStopPending = AtomicBoolean(false)
    private val completedPlaylistIndex = AtomicInteger(NO_INDEX)
    private val suppressRestorePlaybackUntil = AtomicLong(0L)
    private val repairGeneration = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastContentActivityRef: WeakReference<Any>? = null
    private var lastPlayerCoreRef: WeakReference<Any>? = null
    private var blockedLogCount = 0
    private var suppressedLogCount = 0
    private var repairUnavailableLogCount = 0
    private var blockedRestartLogCount = 0
    private var detailLifecycleLogCount = 0
    private val videoDetailActivityNames = setOf(
        "com.bilibili.video.videodetail.VideoDetailsActivity",
        "tv.danmaku.bili.ui.video.VideoDetailsActivity",
        "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity",
        "com.bilibili.ship.theseus.all.UnitedBizDetailsActivity",
        "com.bilibili.multitypeplayerV2.MultiTypeVideoContentActivity",
    )

    override fun startHook() {
        if (!sPrefs.getBoolean("disable_mini_player_auto_play_next", false)) return

        Log.d("startHook: MiniPlayerAutoStopHook")

        val managerClass = findClassOrRecord(
            "com.bilibili.mini.player.biz.DefaultMiniPlayerBizManager"
        ) ?: return

        managerClass.hookAfterAllConstructors { param ->
            forceCompletionPause(param.thisObject)
        }

        managerClass.hookBeforeMethod("D") { param ->
            repairGeneration.incrementAndGet()
            completedAutoStopPending.set(true)
            completedPlaylistIndex.set(currentPlaylistIndex(param.thisObject))
            armRestoreSuppress()
            if (forceCompletionPause(param.thisObject)) {
                logBlocked()
            } else {
                param.result = null
                Log.d("MiniPlayerAutoStop: fallback blocked completion autoplay next")
            }
        }

        managerClass.hookBeforeMethod("C") { param ->
            forceCompletionPause(param.thisObject)
        }

        "kotlin.coroutines.Continuation".from(mClassLoader)?.let { continuationClass ->
            managerClass.hookBeforeMethod("z", continuationClass) { param ->
                forceCompletionPause(param.thisObject)
            }
        }

        managerClass.hookBeforeMethod("g", Int::class.javaObjectType) { param ->
            param.args[0] = COMPLETION_ACTION_PAUSE
        }

        managerClass.hookBeforeMethod(
            "c",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ) {
            forceCompletionPause(it.thisObject)
            if (completedAutoStopPending.get()) {
                val completedIndex = completedPlaylistIndex.get()
                val startIndex = it.args.getOrNull(4) as? Int ?: NO_INDEX
                if (completedIndex == NO_INDEX || startIndex == completedIndex) {
                    armRestoreSuppress()
                    logSameIndexRestart(startIndex, completedIndex)
                } else {
                    clearCompletionPending("mini-player startPlay index=$startIndex completed=$completedIndex")
                }
            }
        }

        managerClass.hookAfterMethod(
            "c",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ) {
            if (!completedAutoStopPending.get()) return@hookAfterMethod
            val completedIndex = completedPlaylistIndex.get()
            val startIndex = it.args.getOrNull(4) as? Int ?: NO_INDEX
            if (completedIndex == NO_INDEX || startIndex == completedIndex) {
                lastContentActivityRef?.get()?.let { activity ->
                    scheduleCompletionRepair(
                        activity,
                        "miniPlayerSameIndexStart",
                        clearWhenDone = false
                    )
                }
            }
        }

        hookRestorePlaybackSuppress()
        hookVideoDetailLifecycle()

        val contentActivityClass = findClassOrRecord(
            "com.bilibili.multitypeplayerV2.MultiTypeVideoContentActivity"
        ) ?: return

        contentActivityClass.hookAfterMethod("onCreate", Bundle::class.java) { param ->
            rememberContentActivity(param.thisObject)
            scheduleCompletionRepair(param.thisObject, "multiTypeOnCreate")
        }
        contentActivityClass.hookAfterMethod("onStart") { param ->
            rememberContentActivity(param.thisObject)
            scheduleCompletionRepair(param.thisObject, "multiTypeOnStart")
        }
        contentActivityClass.hookBeforeMethod("onRestart") { param ->
            rememberContentActivity(param.thisObject)
            if (!completedAutoStopPending.get()) return@hookBeforeMethod
            armRestoreSuppress()
            closeMiniPlayerWindow()
            disableSharedAttach(param.thisObject)
            Log.d("MiniPlayerAutoStop: skip mini-player shared attach after completion stop")
        }
        contentActivityClass.hookAfterMethod("onRestart") { param ->
            rememberContentActivity(param.thisObject)
            scheduleCompletionRepair(param.thisObject, "multiTypeOnRestart")
        }
        contentActivityClass.hookAfterMethod("onResume") { param ->
            rememberContentActivity(param.thisObject)
            scheduleCompletionRepair(param.thisObject, "multiTypeOnResume")
        }
        contentActivityClass.hookAfterMethod(
            "onWindowFocusChanged",
            Boolean::class.javaPrimitiveType
        ) { param ->
            rememberContentActivity(param.thisObject)
            if (param.args[0] == true) {
                scheduleCompletionRepair(param.thisObject, "multiTypeOnWindowFocusChanged")
            }
        }
    }

    private fun forceCompletionPause(manager: Any): Boolean =
        runCatching {
            manager.setObjectField("o", COMPLETION_ACTION_PAUSE)
        }.onFailure {
            Log.e(it)
        }.isSuccess

    private fun rememberContentActivity(activity: Any) {
        lastContentActivityRef = WeakReference(activity)
    }

    private fun rememberPlayerCore(core: Any?) {
        core ?: return
        lastPlayerCoreRef = WeakReference(core)
    }

    private fun currentPlaylistIndex(manager: Any): Int =
        runCatching {
            val current = manager.getObjectFieldOrNull("i") ?: return@runCatching NO_INDEX
            val playlist = manager.getObjectFieldOrNull("a") as? List<*> ?: return@runCatching NO_INDEX
            playlist.indexOf(current)
        }.getOrElse {
            Log.e(it)
            NO_INDEX
        }

    private fun hookRestorePlaybackSuppress() {
        instance.playerCoreServiceV2Class?.apply {
            firstExistingMethodName("getCurrentPosition")?.let { methodName ->
                hookAfterMethod(methodName) { param ->
                    rememberPlayerCore(param.thisObject)
                }
            }
            firstExistingMethodName("getDuration")?.let { methodName ->
                hookAfterMethod(methodName) { param ->
                    rememberPlayerCore(param.thisObject)
                }
            }
            firstExistingMethodName("play")?.let { methodName ->
                hookBeforeMethod(methodName) { param ->
                    suppressRestorePlayback(param, methodName)
                }
            }
            firstExistingMethodName("resume")?.let { methodName ->
                hookBeforeMethod(methodName) { param ->
                    suppressRestorePlayback(param, methodName)
                }
            }
        }
    }

    private fun hookVideoDetailLifecycle() {
        Activity::class.java.hookAfterMethod("onCreate", Bundle::class.java) { param ->
            handleDetailLifecycle(param.thisObject, "detailOnCreate")
        }
        Activity::class.java.hookAfterMethod("onStart") { param ->
            handleDetailLifecycle(param.thisObject, "detailOnStart")
        }
        Activity::class.java.hookAfterMethod("onResume") { param ->
            handleDetailLifecycle(param.thisObject, "detailOnResume")
        }
        Activity::class.java.hookAfterMethod("onNewIntent", Intent::class.java) { param ->
            handleDetailLifecycle(param.thisObject, "detailOnNewIntent")
        }
        Activity::class.java.hookAfterMethod(
            "onWindowFocusChanged",
            Boolean::class.javaPrimitiveType
        ) { param ->
            if (param.args[0] == true) {
                handleDetailLifecycle(param.thisObject, "detailOnWindowFocusChanged")
            }
        }
    }

    private fun handleDetailLifecycle(activity: Any, reason: String) {
        val androidActivity = activity as? Activity ?: return
        if (androidActivity.javaClass.name !in videoDetailActivityNames) return
        rememberContentActivity(androidActivity)
        logDetailLifecycle(androidActivity, reason)
        scheduleCompletionRepair(androidActivity, reason)
    }

    private fun suppressRestorePlayback(param: de.robv.android.xposed.XC_MethodHook.MethodHookParam, methodName: String) {
        if (SystemClock.elapsedRealtime() > suppressRestorePlaybackUntil.get()) return
        rememberPlayerCore(param.thisObject)
        repairCoreCompletionState(param.thisObject, "suppress:$methodName", attempt = 0, source = "playerCore")
        param.result = null
        if (suppressedLogCount >= MAX_SUPPRESS_LOG_COUNT) return
        suppressedLogCount += 1
        Log.d("MiniPlayerAutoStop: suppressed restore $methodName")
    }

    private fun scheduleCompletionRepair(
        activity: Any,
        reason: String,
        clearWhenDone: Boolean = true
    ) {
        if (!completedAutoStopPending.get()) return

        armRestoreSuppress()
        disableSharedAttach(activity)
        val generation = repairGeneration.incrementAndGet()
        REPAIR_DELAYS.forEachIndexed { index, delay ->
            mainHandler.postDelayed({
                if (!completedAutoStopPending.get() || generation != repairGeneration.get()) {
                    return@postDelayed
                }
                repairCompletionState(activity, reason, index)
                if (index == REPAIR_DELAYS.lastIndex && clearWhenDone) {
                    clearCompletionPending("completion restore repair finished reason=$reason")
                }
            }, delay)
        }
        Log.d("MiniPlayerAutoStop: scheduled completion restore repair reason=$reason")
    }

    private fun armRestoreSuppress() {
        val until = SystemClock.elapsedRealtime() + RESTORE_SUPPRESS_WINDOW_MS
        suppressRestorePlaybackUntil.accumulateAndGet(until, ::maxOf)
    }

    private fun disarmRestoreSuppress() {
        suppressRestorePlaybackUntil.set(0L)
    }

    private fun repairCompletionState(activity: Any, reason: String, attempt: Int) {
        disableSharedAttach(activity)
        val activityCore = playerCoreFromActivity(activity)
        val core = activityCore?.also { rememberPlayerCore(it) } ?: lastPlayerCoreRef?.get()
            ?: run {
                logRepairUnavailable(reason, attempt)
                return
            }
        val source = if (activityCore != null) "activityPlayer" else "lastPlayerCore"
        repairCoreCompletionState(core, reason, attempt, source)
    }

    private fun playerCoreFromActivity(activity: Any): Any? =
        runCatching {
            activity.getObjectFieldOrNull("o")
                ?.callMethodOrNull("t4")
                ?.callMethodOrNull("h")
        }.onFailure {
            Log.e(it)
        }.getOrNull()

    private fun repairCoreCompletionState(core: Any, reason: String, attempt: Int, source: String) {
        runCatching {
            rememberPlayerCore(core)
            val duration = core.callMethodOrNullAs<Int>("getDuration") ?: -1
            val current = core.callMethodOrNullAs<Int>("getCurrentPosition") ?: -1

            core.callMethodOrNull("pause")
            if (duration > MIN_SEEKABLE_DURATION_MS) {
                val target = (duration - COMPLETION_SEEK_BACK_MS).coerceAtLeast(0)
                core.callMethodOrNull(instance.seekTo() ?: "seekTo", target)
                core.callMethodOrNull("pause")
            }

            val after = core.callMethodOrNullAs<Int>("getCurrentPosition") ?: -1
            val state = core.callMethodOrNullAs<Int>("getState") ?: -1
            Log.d(
                "MiniPlayerAutoStop: repaired completion state source=$source reason=$reason " +
                    "attempt=$attempt pos=$current->$after duration=$duration state=$state"
            )
        }.onFailure {
            Log.e(it)
        }
    }

    private fun clearCompletionPending(reason: String) {
        completedAutoStopPending.set(false)
        completedPlaylistIndex.set(NO_INDEX)
        disarmRestoreSuppress()
        repairGeneration.incrementAndGet()
        Log.d("MiniPlayerAutoStop: clear pending by $reason")
    }

    private fun disableSharedAttach(activity: Any) {
        runCatching {
            activity.setBooleanField("F", false)
            activity.setBooleanField("G", false)
        }.onFailure {
            Log.e(it)
        }
    }

    private fun closeMiniPlayerWindow() {
        "com.bilibili.mini.player.common.utils.MiniPlayerUtilsKt".from(mClassLoader)
            ?.callStaticMethodOrNull("i")
    }

    private fun findClassOrRecord(className: String): Class<*>? =
        className.from(mClassLoader) ?: run {
            HookStatus.recordFailure(
                ClassNotFoundException(className),
                target = className,
                hookName = "MiniPlayerAutoStopHook"
            )
            Log.d("MiniPlayerAutoStop: class not found $className")
            null
        }

    private fun logBlocked() {
        if (blockedLogCount >= MAX_BLOCK_LOG_COUNT) return
        blockedLogCount += 1
        Log.d(
            "MiniPlayerAutoStop: blocked completion autoplay next index=${completedPlaylistIndex.get()}"
        )
    }

    private fun logSameIndexRestart(startIndex: Int, completedIndex: Int) {
        if (blockedRestartLogCount >= MAX_RESTART_LOG_COUNT) return
        blockedRestartLogCount += 1
        Log.d(
            "MiniPlayerAutoStop: repair same-video mini-player restart " +
                "index=$startIndex completed=$completedIndex"
        )
    }

    private fun logRepairUnavailable(reason: String, attempt: Int) {
        if (repairUnavailableLogCount >= MAX_REPAIR_UNAVAILABLE_LOG_COUNT) return
        repairUnavailableLogCount += 1
        Log.d("MiniPlayerAutoStop: repair skipped no player reason=$reason attempt=$attempt")
    }

    private fun logDetailLifecycle(activity: Activity, reason: String) {
        if (!completedAutoStopPending.get()) return
        if (detailLifecycleLogCount >= MAX_DETAIL_LIFECYCLE_LOG_COUNT) return
        detailLifecycleLogCount += 1
        Log.d(
            "MiniPlayerAutoStop: detail lifecycle reason=$reason " +
                "activity=${activity.javaClass.name}"
        )
    }

    companion object {
        private const val NO_INDEX = -1
        private const val COMPLETION_ACTION_PAUSE = 1
        private const val MAX_BLOCK_LOG_COUNT = 10
        private const val MAX_RESTART_LOG_COUNT = 20
        private const val MAX_SUPPRESS_LOG_COUNT = 20
        private const val MAX_REPAIR_UNAVAILABLE_LOG_COUNT = 20
        private const val MAX_DETAIL_LIFECYCLE_LOG_COUNT = 20
        private const val MIN_SEEKABLE_DURATION_MS = 2_000
        private const val COMPLETION_SEEK_BACK_MS = 500
        private const val RESTORE_SUPPRESS_WINDOW_MS = 4_000L
        private val REPAIR_DELAYS = longArrayOf(0L, 200L, 600L, 1_200L, 2_500L)
    }
}
