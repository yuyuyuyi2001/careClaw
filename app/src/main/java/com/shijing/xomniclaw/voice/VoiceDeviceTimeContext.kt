package com.shijing.xomniclaw.voice

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 将设备本地日期、星期、时刻注入 VLM，避免模型声称「无法获取实时日期」。
 */
internal object VoiceDeviceTimeContext {

    private val WEEKDAYS_ZH = arrayOf(
        "",
        "星期日",
        "星期一",
        "星期二",
        "星期三",
        "星期四",
        "星期五",
        "星期六",
    )

    /**
     * 短 system 片段，与主提示词并列下发。
     */
    fun buildSystemLine(): String {
        val cal = Calendar.getInstance()
        val dateStr = SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(cal.time)
        val timeStr = SimpleDateFormat("HH:mm", Locale.CHINA).format(cal.time)
        val weekday = WEEKDAYS_ZH.getOrElse(cal.get(Calendar.DAY_OF_WEEK)) { "" }
        return "【设备本地时间】$dateStr $weekday $timeStr。若用户询问今天星期几、几号、现在几点等，请直接根据以上信息用口语简短回答，不要声称无法获取实时时间。"
    }
}
