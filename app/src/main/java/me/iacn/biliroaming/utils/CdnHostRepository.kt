package me.iacn.biliroaming.utils

data class CdnHost(val name: String, val host: String)

object CdnHostRepository {
    const val DEFAULT_HOST = "\$1"

    const val BOS_HOST = "upos-sz-mirrorbos.bilivideo.com"
    const val COS_HOST = "upos-sz-mirrorcos.bilivideo.com"
    const val COSB_HOST = "upos-sz-mirrorcosb.bilivideo.com"
    const val COSO1_HOST = "upos-sz-mirrorcoso1.bilivideo.com"
    const val HW_HOST = "upos-sz-mirrorhw.bilivideo.com"
    const val HWB_HOST = "upos-sz-mirrorhwb.bilivideo.com"
    const val HWO1_HOST = "upos-sz-mirrorhwo1.bilivideo.com"
    const val HW_08C_HOST = "upos-sz-mirror08c.bilivideo.com"
    const val HW_08H_HOST = "upos-sz-mirror08h.bilivideo.com"
    const val HW_08CT_HOST = "upos-sz-mirror08ct.bilivideo.com"
    const val ALI_HOST = "upos-sz-mirrorali.bilivideo.com"
    const val ALIB_HOST = "upos-sz-mirroralib.bilivideo.com"
    const val ALIO1_HOST = "upos-sz-mirroralio1.bilivideo.com"
    const val AKAMAI_HOST = "upos-hz-mirrorakam.akamaized.net"
    const val ALIOV_HOST = "upos-sz-mirroraliov.bilivideo.com"
    const val HWOV_HOST = "upos-sz-mirrorhwov.bilivideo.com"
    const val COSOV_HOST = "upos-sz-mirrorcosov.bilivideo.com"
    const val HK_BCACHE_HOST = "cn-hk-eq-bcache-01.bilivideo.com"
    const val TF_HW_HOST = "upos-tf-all-hw.bilivideo.com"
    const val TF_TX_HOST = "upos-tf-all-tx.bilivideo.com"

    // Regional candidates are a conservative subset of CCB's public bilivideo.com CDN data:
    // https://github.com/Kanda-Akihito-Kun/ccb
    val hosts = listOf(
        CdnHost("不替换", DEFAULT_HOST),
        CdnHost("ali（阿里）", ALI_HOST),
        CdnHost("alib（阿里）", ALIB_HOST),
        CdnHost("alio1（阿里）", ALIO1_HOST),
        CdnHost("bos（百度）", BOS_HOST),
        CdnHost("cos（腾讯）", COS_HOST),
        CdnHost("cosb（腾讯）", COSB_HOST),
        CdnHost("coso1（腾讯）", COSO1_HOST),
        CdnHost("hw（华为）", HW_HOST),
        CdnHost("hwb（华为）", HWB_HOST),
        CdnHost("hwo1（华为）", HWO1_HOST),
        CdnHost("08c（华为）", HW_08C_HOST),
        CdnHost("08h（华为）", HW_08H_HOST),
        CdnHost("08ct（华为）", HW_08CT_HOST),
        CdnHost("tf_hw（华为）", TF_HW_HOST),
        CdnHost("tf_tx（腾讯）", TF_TX_HOST),
        CdnHost("akamai（Akamai海外）", AKAMAI_HOST),
        CdnHost("aliov（阿里海外）", ALIOV_HOST),
        CdnHost("cosov（腾讯海外）", COSOV_HOST),
        CdnHost("hwov（华为海外）", HWOV_HOST),
        CdnHost("hk_bcache（Bilibili海外）", HK_BCACHE_HOST),
        CdnHost("上海 电信 01-01", "cn-sh-ct-01-01.bilivideo.com"),
        CdnHost("上海 电信 01-24", "cn-sh-ct-01-24.bilivideo.com"),
        CdnHost("上海 office bcache", "cn-sh-office-bcache-01.bilivideo.com"),
        CdnHost("北京 联通 03-14", "cn-bj-cc-03-14.bilivideo.com"),
        CdnHost("北京 方正 01-04", "cn-bj-fx-01-04.bilivideo.com"),
        CdnHost("北京 教育网 01-05", "cn-bj-se-01-05.bilivideo.com"),
        CdnHost("广州 移动 01-02", "cn-gdgz-cm-01-02.bilivideo.com"),
        CdnHost("广州 方正 01-01", "cn-gdgz-fx-01-01.bilivideo.com"),
        CdnHost("广州 广电 01-01", "cn-gdgz-gd-01-01.bilivideo.com"),
        CdnHost("深圳 dynqn", "upos-sz-dynqn.bilivideo.com"),
        CdnHost("深圳 estgcos", "upos-sz-estgcos.bilivideo.com"),
        CdnHost("深圳 estghw", "upos-sz-estghw.bilivideo.com"),
        CdnHost("深圳 mirrorbd", "upos-sz-mirrorbd.bilivideo.com"),
        CdnHost("深圳 mirrorcosdisp", "upos-sz-mirrorcosdisp.bilivideo.com"),
        CdnHost("深圳 mirrorhwdisp", "upos-sz-mirrorhwdisp.bilivideo.com"),
        CdnHost("杭州 移动 01-01", "cn-zjhz-cm-01-01.bilivideo.com"),
        CdnHost("杭州 联通 01-01", "cn-zjhz-cu-01-01.bilivideo.com"),
        CdnHost("杭州 联通 v-02", "cn-zjhz-cu-v-02.bilivideo.com"),
        CdnHost("成都 移动 03-02", "cn-sccd-cm-03-02.bilivideo.com"),
        CdnHost("成都 电信 01-02", "cn-sccd-ct-01-02.bilivideo.com"),
        CdnHost("成都 联通 01-02", "cn-sccd-cu-01-02.bilivideo.com"),
        CdnHost("南京 方正 02-05", "cn-jsnj-fx-02-05.bilivideo.com"),
        CdnHost("南京 广电 01-02", "cn-jsnj-gd-01-02.bilivideo.com"),
        CdnHost("武汉 移动 01-01", "cn-hbwh-cm-01-01.bilivideo.com"),
        CdnHost("武汉 方正 01-01", "cn-hbwh-fx-01-01.bilivideo.com"),
        CdnHost("福建 福州方正 01-01", "cn-fjfz-fx-01-01.bilivideo.com"),
        CdnHost("福建 泉州移动 01-01", "cn-fjqz-cm-01-01.bilivideo.com"),
        CdnHost("西安 移动 01-01", "cn-sxxa-cm-01-01.bilivideo.com"),
        CdnHost("西安 电信 03-02", "cn-sxxa-ct-03-02.bilivideo.com"),
        CdnHost("郑州 移动 01-01", "cn-hnzz-cm-01-01.bilivideo.com"),
        CdnHost("郑州 方正 01-01", "cn-hnzz-fx-01-01.bilivideo.com"),
        CdnHost("香港 eq 01-01", "cn-hk-eq-01-01.bilivideo.com"),
        CdnHost("香港 eq bcache-13", "cn-hk-eq-bcache-13.bilivideo.com"),
        CdnHost("外建 gotcha04b", "d1--cn-gotcha04b.bilivideo.com"),
        CdnHost("外建 gotcha208b", "d1--cn-gotcha208b.bilivideo.com"),
        CdnHost("海外 gotcha01", "d1--ov-gotcha01.bilivideo.com"),
        CdnHost("海外 gotcha207", "d1--ov-gotcha207.bilivideo.com"),
    ).distinctBy { it.host }

    fun entries(defaultTitle: String = hosts.first().name): Array<CharSequence> =
        hosts.mapIndexed { index, host ->
            if (index == 0) defaultTitle else host.name
        }.map { it as CharSequence }.toTypedArray()

    fun values(): Array<CharSequence> = hosts.map { it.host as CharSequence }.toTypedArray()

    fun backupHostsFor(selectedHost: String, isLocatedCn: Boolean): List<String> {
        val preferred = when {
            selectedHost == DEFAULT_HOST -> if (isLocatedCn) {
                listOf(HW_HOST, COS_HOST, ALI_HOST)
            } else {
                listOf(ALIOV_HOST, COSOV_HOST, HK_BCACHE_HOST)
            }

            selectedHost.contains("cos", ignoreCase = true) ->
                listOf(HW_HOST, ALI_HOST, HK_BCACHE_HOST)

            selectedHost.contains("hw", ignoreCase = true) ->
                listOf(ALI_HOST, COS_HOST, HK_BCACHE_HOST)

            selectedHost.contains("ali", ignoreCase = true) ->
                listOf(HW_HOST, COS_HOST, HK_BCACHE_HOST)

            selectedHost.isOverseaLikeHost() ->
                listOf(ALIOV_HOST, COSOV_HOST, HK_BCACHE_HOST, AKAMAI_HOST)

            selectedHost.startsWith("cn-") || selectedHost.contains("gotcha") ->
                listOf(HW_HOST, COS_HOST, ALI_HOST)

            else -> listOf(ALI_HOST, HW_HOST, COS_HOST)
        }
        return (preferred + listOf(ALI_HOST, HW_HOST, COS_HOST, HK_BCACHE_HOST))
            .filter { it != selectedHost }
            .distinct()
            .take(2)
    }

    fun String.isOverseaLikeHost(): Boolean =
        contains("akamaized.net", ignoreCase = true) ||
                contains("aliov", ignoreCase = true) ||
                contains("cosov", ignoreCase = true) ||
                contains("hwov", ignoreCase = true) ||
                contains("bstar", ignoreCase = true) ||
                contains("-hk-", ignoreCase = true) ||
                contains("hk-eq", ignoreCase = true) ||
                contains("--ov-gotcha", ignoreCase = true)
}
