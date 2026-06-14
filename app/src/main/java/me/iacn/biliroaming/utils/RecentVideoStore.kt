package me.iacn.biliroaming.utils

import android.net.Uri

object RecentVideoStore {
    private const val KEY_AID = "speed_test_recent_aid"
    private const val KEY_BVID = "speed_test_recent_bvid"
    private const val KEY_CID = "speed_test_recent_cid"
    private const val KEY_UPDATED_AT = "speed_test_recent_updated_at"

    data class RecentVideo(
        val aid: Long,
        val bvid: String,
        val cid: Long,
        val updatedAt: Long
    ) {
        val referer: String
            get() = "https://www.bilibili.com/video/$bvid"

        val displayName: String
            get() = "$bvid / cid $cid"
    }

    fun save(aid: Long, bvid: String, cid: Long, source: String) {
        if (aid <= 0 || cid <= 0 || bvid.isBlank()) return
        val oldAid = sPrefs.getLong(KEY_AID, -1L)
        val oldCid = sPrefs.getLong(KEY_CID, -1L)
        val oldBvid = sPrefs.getString(KEY_BVID, null)
        val changed = oldAid != aid || oldCid != cid || oldBvid != bvid
        sPrefs.edit()
            .putLong(KEY_AID, aid)
            .putString(KEY_BVID, bvid)
            .putLong(KEY_CID, cid)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
        if (changed) {
            Log.d("RecentVideo: source=$source bvid=$bvid aid=$aid cid=$cid")
        }
    }

    fun saveFromBvid(bvid: String, cid: Long, source: String) {
        val aid = runCatchingOrNull { bv2av(bvid) } ?: return
        save(aid, bvid, cid, source)
    }

    fun parsePositiveLong(value: Any?): Long? {
        val parsed = when (value) {
            is Number -> value.toLong()
            is String -> numberRegex.find(value)?.value?.toLongOrNull()
            else -> numberRegex.find(value?.toString().orEmpty())?.value?.toLongOrNull()
        }
        return parsed?.takeIf { it > 0 }
    }

    fun latest(): RecentVideo? {
        val aid = sPrefs.getLong(KEY_AID, -1L)
        val cid = sPrefs.getLong(KEY_CID, -1L)
        val bvid = sPrefs.getString(KEY_BVID, null).orEmpty()
        val updatedAt = sPrefs.getLong(KEY_UPDATED_AT, 0L)
        if (aid <= 0 || cid <= 0 || bvid.isBlank()) return null
        return RecentVideo(aid, bvid, cid, updatedAt)
    }

    fun Uri.videoBvid(): String? {
        getQueryParameter("bvid")?.takeIf { it.isNotBlank() }?.let { return it }
        val rawId = lastPathSegment?.takeIf { it.isNotBlank() } ?: return null
        if (rawId.startsWith("BV", ignoreCase = true)) return rawId
        return rawId.toLongOrNull()?.let { av2bv(it) }
    }

    private val numberRegex = Regex("""\d+""")
}
