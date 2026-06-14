package me.iacn.biliroaming.utils

import me.iacn.biliroaming.BuildConfig
import org.json.JSONObject

object HookStatus {
    private const val KEY_FAILED_PREFS = "hook_status_failed_prefs"
    private const val KEY_FAILED_DETAILS = "hook_status_failed_details"
    private const val MAX_DETAIL_LENGTH = 140

    private val currentHook = ThreadLocal<String?>()

    private val hookPreferenceKeys = mapOf(
        "BangumiPlayUrlHook" to setOf(
            "main_func",
            "custom_server",
            "allow_download",
            "fix_download",
            "play_arc_conf"
        ),
        "PegasusHook" to setOf(
            "home_filter",
            "remove_video_relate_promote",
            "remove_video_relate_only_av",
            "remove_video_relate_nothing",
            "block_upper_recommend_ad",
            "block_view_page_ads",
            "pegasus_cover_ratio"
        ),
        "ProtoBufHook" to setOf(
            "play_arc_conf",
            "block_comment_guide",
            "disable_main_page_story",
            "remove_video_honor",
            "remove_video_UgcSeason",
            "remove_video_cmd_dms",
            "remove_up_vip_label",
            "filter_search",
            "filter_comment",
            "customize_dynamic",
            "block_live_order",
            "block_word_search",
            "block_modules",
            "block_upper_recommend_ad",
            "block_video_comment",
            "block_view_page_ads"
        ),
        "PlayArcConfHook" to setOf("play_arc_conf"),
        "TryWatchVipQualityHook" to setOf("disable_try_watch_vip_quality"),
        "VideoQualityHook" to setOf("half_screen_quality", "full_screen_quality"),
        "LiveQualityHook" to setOf("live_quality"),
        "UposReplaceHook" to setOf(
            "upos_host",
            "test_upos",
            "force_upos",
            "block_pcdn",
            "block_pcdn_live"
        ),
        "P2pHook" to setOf("block_pcdn", "block_pcdn_live"),
        "SettingHook" to setOf("drawer", "customize_drawer", "add_custom_button"),
        "DrawerHook" to setOf(
            "drawer",
            "customize_drawer",
            "purify_drawer_reddot",
            "drawer_style_switch",
            "drawer_style",
            "remove_vip_section"
        ),
        "VipSectionHook" to setOf("remove_vip_section"),
        "EnvHook" to setOf("enable_av"),
        "SplashHook" to setOf("custom_splash", "custom_splash_logo", "full_splash"),
        "MusicNotificationHook" to setOf("music_notification"),
        "SkipVideoAd" to setOf("skip_video_ad"),
        "RewardAdHook" to setOf("skip_reward_ad"),
        "SpeedHook" to setOf("default_speed"),
        "LongPressSpeed" to setOf("long_press_speed"),
        "PlayerLongPressHook" to setOf("forbid_player_long_click_accelerate"),
        "StoryPlayerAdHook" to setOf("purify_story_video_ad"),
    )

    fun clearForNewRun() {
        runCatching {
            sCaches.edit()
                .remove(KEY_FAILED_PREFS)
                .remove(KEY_FAILED_DETAILS)
                .apply()
        }
    }

    fun <T> withHook(name: String, block: () -> T): T {
        val previous = currentHook.get()
        currentHook.set(name)
        return try {
            block()
        } finally {
            currentHook.set(previous)
        }
    }

    fun recordFailure(error: Throwable, target: String? = null, hookName: String? = null) {
        runCatching {
            val keys = preferenceKeysFor(error, target, hookName)
            if (keys.isEmpty()) return

            val detail = buildDetail(error, target)
            val failedPrefs = sCaches.getStringSet(KEY_FAILED_PREFS, emptySet())
                ?.toMutableSet() ?: mutableSetOf()
            val details = JSONObject(sCaches.getString(KEY_FAILED_DETAILS, "{}").orEmpty())

            keys.forEach { key ->
                failedPrefs.add(key)
                details.put(key, detail)
            }

            if (BuildConfig.DEBUG) {
                Log.d("HookStatus: recordFailure keys=${keys.joinToString()} detail=$detail")
            }

            sCaches.edit()
                .putStringSet(KEY_FAILED_PREFS, failedPrefs)
                .putString(KEY_FAILED_DETAILS, details.toString())
                .apply()
        }
    }

    fun failedPreferenceKeys(): Set<String> =
        sCaches.getStringSet(KEY_FAILED_PREFS, emptySet()).orEmpty()

    fun detailForPreference(key: String): String? = runCatchingOrNull {
        JSONObject(sCaches.getString(KEY_FAILED_DETAILS, "{}").orEmpty()).optString(key)
            .takeIf { it.isNotBlank() }
    }

    private fun preferenceKeysFor(
        error: Throwable,
        target: String?,
        hookName: String?
    ): Set<String> {
        val names = buildList {
            hookName?.let { add(it) }
            currentHook.get()?.let { add(it) }
            inferHookName(error)?.let { add(it) }
        }
        val byTarget = preferenceKeysForTarget(target, error, names)
        if (byTarget.isNotEmpty()) return byTarget

        return names.asSequence()
            .mapNotNull { hookPreferenceKeys[it] }
            .flatten()
            .toSet()
    }

    private fun inferHookName(error: Throwable): String? =
        error.stackTrace.firstNotNullOfOrNull { frame ->
            frame.className.substringAfterLast('.')
                .substringBefore('$')
                .takeIf { it in hookPreferenceKeys }
        }

    private fun preferenceKeysForTarget(
        target: String?,
        error: Throwable,
        hookNames: List<String>
    ): Set<String> {
        val text = listOfNotNull(target, error.message, error.javaClass.name).joinToString("\n")
        return when {
            "HomeUserCenterFragment" in text || "homeUserCenter" in text -> when {
                "VipSectionHook" in hookNames -> hookPreferenceKeys.getValue("VipSectionHook")
                "DrawerHook" in hookNames -> hookPreferenceKeys.getValue("DrawerHook")
                "SettingHook" in hookNames -> hookPreferenceKeys.getValue("SettingHook")
                else -> hookPreferenceKeys.getValue("SettingHook")
            }

            "preBuiltConfig" in text || "dataSp" in text || "dataSP" in text ->
                hookPreferenceKeys.getValue("EnvHook")

            "PlayerCoreServiceV2#G1" in text ->
                setOf("skip_video_ad")

            "PlayerMoss#executePlayViewUnite" in text ||
                    "PlayerMoss#playViewUnite" in text ||
                    "PlayViewUniteReq" in text -> when {
                "SkipVideoAd" in hookNames -> setOf("skip_video_ad")
                "PlayArcConfHook" in hookNames -> setOf("play_arc_conf")
                "BangumiPlayUrlHook" in hookNames ->
                    hookPreferenceKeys.getValue("BangumiPlayUrlHook")

                else -> setOf("play_arc_conf", "skip_video_ad", "main_func")
            }

            "RelatesFeedReq" in text || "ViewReq" in text ->
                setOf(
                    "home_filter",
                    "remove_video_relate_promote",
                    "remove_video_relate_only_av",
                    "remove_video_relate_nothing",
                    "block_view_page_ads"
                )

            "TFInfoReq" in text ->
                setOf("remove_video_cmd_dms")

            "playerSettingHelper" in text ||
                    "qualityStrategyProvider" in text ||
                    "getDefaultQn" in text ||
                    "selectQuality" in text ->
                setOf("half_screen_quality", "full_screen_quality")

            "vipQualityTrialService" in text || "canTrial" in text ->
                setOf("disable_try_watch_vip_quality")

            "RewardAd" in text || "rewardFlag" in text ->
                setOf("skip_reward_ad")

            else -> emptySet()
        }
    }

    private fun buildDetail(error: Throwable, target: String?): String {
        val message = error.message?.takeIf { it.isNotBlank() }
        val raw = listOfNotNull(target, error.javaClass.simpleName, message).joinToString(": ")
        return raw.take(MAX_DETAIL_LENGTH)
    }
}
