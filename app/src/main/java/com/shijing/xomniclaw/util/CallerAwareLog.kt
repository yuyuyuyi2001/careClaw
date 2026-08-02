package com.shijing.xomniclaw.util

import android.util.Log

/**
 * Android 日志包装器：
 * 在日志正文前附加 [文件名:行号] 方法名 -> 前缀，便于快速定位打印来源。
 */
object CallerAwareLog {
    private const val UNKNOWN_TAG = "[unknown:0] unknown ->"
    private const val SELF_CLASS = "com.shijing.xomniclaw.util.CallerAwareLog"

    private fun buildCallerPrefix(): String {
        val frame = Throwable().stackTrace.firstOrNull { element ->
            val cls = element.className
            !cls.startsWith("java.lang.Thread") &&
                !cls.startsWith("dalvik.system") &&
                !cls.startsWith(SELF_CLASS)
        } ?: return UNKNOWN_TAG

        val file = frame.fileName ?: frame.className.substringAfterLast('.')
        val line = frame.lineNumber
        val method = frame.methodName
        return "[$file:$line] $method ->"
    }

    private fun withCaller(message: String): String = "${buildCallerPrefix()} $message"

    fun d(tag: String, message: String): Int = Log.d(tag, withCaller(message))

    fun i(tag: String, message: String): Int = Log.i(tag, withCaller(message))

    fun w(tag: String, message: String): Int = Log.w(tag, withCaller(message))

    fun e(tag: String, message: String, tr: Throwable? = null): Int {
        return if (tr != null) {
            Log.e(tag, withCaller(message), tr)
        } else {
            Log.e(tag, withCaller(message))
        }
    }
}
