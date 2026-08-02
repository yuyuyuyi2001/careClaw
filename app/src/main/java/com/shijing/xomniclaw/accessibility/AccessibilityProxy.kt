/**
 * OmniClaw Source Reference:
 * - ../omniclaw/src/gateway/(all)
 *
 * OmniClaw adaptation: accessibility integration.
 */
package com.shijing.xomniclaw.accessibility

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.shijing.xomniclaw.accessibility.service.AccessibilityBinderService
import com.shijing.xomniclaw.accessibility.service.ViewNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

object AccessibilityProxy {
    private const val TAG = "AccessibilityProxy"

    private val service get() = AccessibilityBinderService.serviceInstance

    private val _isConnected = MutableLiveData(false)
    val isConnected: LiveData<Boolean> = _isConnected

    // Cached UI tree
    private data class CachedUITree(
        val nodes: List<ViewNode>,
        val timestamp: Long,
        val packageName: String
    )

    private var cachedTree: CachedUITree? = null
    private val cacheValidityMs = 500L

    suspend fun dumpViewTree(useCache: Boolean = true): List<ViewNode> = withContext(Dispatchers.IO) {
        ensureConnectedWithRetry()
        val svc = service
        if (svc == null) {
            Log.w(TAG, "Service not available for dumpViewTree after retry")
            _isConnected.postValue(false)
            return@withContext emptyList()
        }
        _isConnected.postValue(true)

        val currentPkg = svc.currentPackageName
        val now = System.currentTimeMillis()

        // Return cache if valid
        if (useCache && cachedTree != null) {
            val cache = cachedTree!!
            if (cache.packageName == currentPkg &&
                (now - cache.timestamp) < cacheValidityMs) {
                return@withContext cache.nodes
            }
        }

        val nodes = svc.dumpView()
        cachedTree = CachedUITree(nodes, now, currentPkg)
        nodes
    }

    suspend fun tap(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        ensureConnectedWithRetry(requireReady = true)
        service?.performClickAt(x.toFloat(), y.toFloat(), isLongClick = false) ?: false
    }

    suspend fun longPress(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        ensureConnectedWithRetry(requireReady = true)
        service?.performClickAt(x.toFloat(), y.toFloat(), isLongClick = true) ?: false
    }

    suspend fun swipe(
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        durationMs: Long = 300
    ): Boolean = withContext(Dispatchers.IO) {
        val svc = service
        if (svc == null) {
            Log.w(TAG, "Service not available for swipe")
            return@withContext false
        }
        svc.performSwipe(startX.toFloat(), startY.toFloat(), endX.toFloat(), endY.toFloat())
        true
    }

    fun pressHome(): Boolean {
        val svc = service ?: return false
        svc.pressHomeButton()
        return true
    }

    fun pressBack(): Boolean {
        val svc = service ?: return false
        svc.pressBackButton()
        return true
    }

    fun inputText(text: String): Boolean {
        val svc = service ?: return false
        return svc.inputText(text)
    }

    suspend fun getCurrentPackageName(): String = withContext(Dispatchers.IO) {
        service?.currentPackageName ?: ""
    }

    /**
     * 异步检查服务是否就绪
     */
    suspend fun isServiceReadyAsync(): Boolean {
        return service?.rootInActiveWindow != null
    }

    /**
     * 同步检查服务是否就绪
     */
    fun isServiceReady(): Boolean {
        return service?.rootInActiveWindow != null
    }

    // ===== MediaProjection (Screenshot) Methods =====

    fun isMediaProjectionGranted(): Boolean {
        return MediaProjectionHelper.isMediaProjectionGranted()
    }

    /**
     * 截取当前屏幕，返回保存路径（失败时返回空串）。
     *
     * 策略（按优先级，前一种失败才走后一种）：
     *
     * 1. **Root + screencap**（最高优先级）
     *    - 完全绕过 MediaProjection 与 AccessibilityService；
     *    - 不受息屏 / 后台 / OEM 政策影响，定时任务在锁屏场景下唯一稳定通路；
     *    - 仅在设备 root 且本应用已被 Magisk/KernelSU 授权时可用，否则瞬间返回 null 走下一级。
     *
     * 2. **AccessibilityService.takeScreenshot**（API 30+，需要无障碍服务声明 canTakeScreenshot）
     *    - 不依赖 MediaProjection token；
     *    - 系统对调用频率有节流（每无障碍服务约每秒 1 张），常规调度场景够用；
     *    - 在部分国产 ROM 上同样可能被限流，但比 MediaProjection 宽松很多。
     *
     * 3. **MediaProjection**（兜底）
     *    - 用户在前台已经授权时可用，对极端高频抓帧场景仍是更优解；
     *    - 在 Android 14+ / ColorOS 上息屏瞬间会被系统 revoke，息屏场景基本不可用。
     *
     * 注：`ensureConnectedWithRetry()` 只是为了让 a11y 截图路径有可用的 service 句柄。
     * root 路径不依赖 service，所以放在 ensureConnected 之前先尝试，可以更快失败回落。
     */
    suspend fun captureScreen(): String = withContext(Dispatchers.IO) {
        // 1) Root 路径：最高优先级、不依赖任何 Android 组件状态。
        val rootResult = try {
            RootScreencap.capture()
        } catch (e: Exception) {
            Log.w(TAG, "Root screencap threw, fallback to a11y", e)
            null
        }
        if (rootResult != null) {
            return@withContext rootResult.second
        }

        // 2) 走 a11y 之前必须保证服务已连接。
        ensureConnectedWithRetry()

        val a11yResult = try {
            service?.takeScreenshotViaA11y()
        } catch (e: Exception) {
            Log.w(TAG, "A11y takeScreenshot threw, fallback to MediaProjection", e)
            null
        }
        if (a11yResult != null) {
            return@withContext a11yResult.second
        }

        // 3) MediaProjection 兜底。
        Log.d(TAG, "A11y screenshot unavailable, fallback to MediaProjection")
        MediaProjectionHelper.captureScreen()?.second ?: ""
    }

    fun getMediaProjectionStatus(): String {
        return MediaProjectionHelper.getDetailedStatus()
    }

    /**
     * 确保服务已连接；可选地等待 AccessibilityService 真正 ready。
     */
    private suspend fun ensureConnectedWithRetry(requireReady: Boolean = false) {
        if (service != null && (!requireReady || checkServiceReadyOnce())) {
            _isConnected.postValue(true)
            return
        }

        Log.w(TAG, "Service not connected/ready, waiting... requireReady=$requireReady")

        repeat(3) { attempt ->
            val connected = kotlinx.coroutines.withTimeoutOrNull(2000L) {
                while (service == null) {
                    delay(100)
                }
                true
            } == true

            if (!connected) {
                Log.w(TAG, "Attempt ${attempt + 1} failed: serviceInstance still null")
                delay(500)
                return@repeat
            }

            if (!requireReady) {
                Log.d(TAG, "Service connected on attempt ${attempt + 1}")
                _isConnected.postValue(true)
                return
            }

            val ready = kotlinx.coroutines.withTimeoutOrNull(2500L) {
                while (!checkServiceReadyOnce()) {
                    delay(100)
                }
                true
            } == true

            if (ready) {
                Log.d(TAG, "Service ready on attempt ${attempt + 1}")
                _isConnected.postValue(true)
                return
            }

            Log.w(TAG, "Attempt ${attempt + 1} failed: service not ready")
            delay(500)
        }

        _isConnected.postValue(false)
        if (requireReady) {
            throw IllegalStateException("Accessibility service not ready after 3 retry attempts")
        } else {
            throw IllegalStateException("Accessibility service not connected after 3 retry attempts")
        }
    }

    private fun checkServiceReadyOnce(): Boolean {
        return try {
            service?.rootInActiveWindow != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check service ready state", e)
            false
        }
    }
}
