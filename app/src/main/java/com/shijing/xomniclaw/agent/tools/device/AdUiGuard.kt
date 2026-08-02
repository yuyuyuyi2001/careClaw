package com.shijing.xomniclaw.agent.tools.device

import java.util.Locale

/**
 * Heuristic guard: reduce accidental taps on ad / promotion surfaces.
 * Not perfect; false positives/negatives possible. Use [ignore_ad_guard] on device tool to override.
 */
internal object AdUiGuard {

    private val dismissTexts = listOf(
        "跳过", "关闭", "不感兴趣", "稍后", "取消", "知道了", "暂不", "跳过广告", "关闭广告",
        "Skip", "Close", "Dismiss", "Later"
    )

    private val adClassFragments = listOf(
        "adview", "bannerad", "nativead", "feedad", "ttad", "gdt", "pangle", "mads",
        "mobclix", "windmill", "csjad", "unionad", "ksad", "splash", "interstitial",
        "reward", "incentive", "advert", "promo"
    )

    private val adTextPhrases = listOf(
        "广告", "赞助", "推广", "信息流", "Sponsored", "Advertisement",
        "立即下载", "点击下载", "免费下载", "查看详情", "立即打开", "打开看看",
        "观看完整", "看视频", "激励视频", "下载APP", "下载应用", "领取奖励",
        "再看一个"
    )

    fun isLikelyAdvertisement(node: RefNode?): Boolean {
        if (node == null) return false
        if (isDismissTarget(node)) return false

        val cls = node.className.orEmpty().lowercase(Locale.ROOT)
        if (adClassFragments.any { cls.contains(it) }) return true

        val text = node.text.orEmpty()
        if (adTextPhrases.any { text.contains(it, ignoreCase = true) }) return true

        return false
    }

    private fun isDismissTarget(node: RefNode): Boolean {
        val t = node.text?.trim().orEmpty()
        if (t.isEmpty()) return false
        if (t.length > 32) return false
        return dismissTexts.any { hint ->
            t.equals(hint, ignoreCase = true) ||
                t.startsWith(hint, ignoreCase = true)
        }
    }
}
