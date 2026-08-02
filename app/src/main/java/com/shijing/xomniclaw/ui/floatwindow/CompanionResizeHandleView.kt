package com.shijing.xomniclaw.ui.floatwindow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 屏内替身悬浮窗右下角的缩放把手。
 *
 * 以加厚 L 型蓝条 + 阴影/渐变表现「可握持」的厚度，中心绘白色小三角提示沿对角线推拉以改尺寸。
 */
class CompanionResizeHandleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density: Float = resources.displayMetrics.density

    /** L 型单边视觉厚度（约 7dp，较前版更细，避免右下角过于臃肿） */
    private val armWidthBase: Float = 7f * density

    private val lPath = Path()
    private val bevelPath = Path()
    private val gripPath = Path()
    private val tempRect = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
        color = Color.parseColor("#B3E3F2FF")
    }

    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = 240
    }

    private val gripDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = 200
    }

    init {
        // 与 setShadowLayer 绘制非文字 Path 时，软件层在多数机型上阴影更稳定
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return

        val t = min(armWidthBase, min(w, h) * 0.22f)

        lPath.rewind()
        // 底边条 + 右侧条，在右下角形成 L
        lPath.addRect(0f, h - t, w, h, Path.Direction.CW)
        lPath.addRect(w - t, 0f, w, h - t, Path.Direction.CW)

        lPath.computeBounds(tempRect, true)
        val grad = LinearGradient(
            tempRect.left,
            tempRect.top,
            tempRect.right,
            tempRect.bottom,
            intArrayOf(
                Color.parseColor("#4FC3F7"),
                Color.parseColor("#1E88E5"),
                Color.parseColor("#0D47A1"),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        fillPaint.shader = grad
        // 下偏阴影（偏移略小，减轻被圆角卡片底边裁剪）
        fillPaint.setShadowLayer(5f * density, 0.35f * density, 1.6f * density, 0x44000000)
        canvas.drawPath(lPath, fillPaint)
        fillPaint.shader = null
        fillPaint.setShadowLayer(0f, 0f, 0f, 0)

        // 内沿高光线，让 L 边更清晰
        val inset = 1f * density
        bevelPath.rewind()
        bevelPath.moveTo(inset * 1.2f, h - t * 0.55f)
        bevelPath.lineTo(w - t * 0.55f, h - t * 0.55f)
        bevelPath.lineTo(w - t * 0.55f, inset * 1.2f)
        canvas.drawPath(bevelPath, bevelPaint)

        drawGripHints(canvas, w, h, t)
    }

    /**
     * 三角为主、两点为辅，模拟设计稿中的「对角拖拽」暗示。
     */
    private fun drawGripHints(canvas: Canvas, w: Float, h: Float, t: Float) {
        val cx = w - t * 0.58f
        val cy = h - t * 0.58f
        val side = t * 0.42f
        gripPath.rewind()
        // 顶点朝向左上方（示意沿左上↘右下对角缩放）
        gripPath.moveTo(cx - side * 0.35f, cy - side * 0.75f)
        gripPath.lineTo(cx + side * 0.72f, cy + side * 0.42f)
        gripPath.lineTo(cx - side * 0.58f, cy + side * 0.62f)
        gripPath.close()
        canvas.drawPath(gripPath, gripPaint)

        val dotR = maxOf(1.8f * density, t * 0.09f)
        val o = t * 0.14f
        canvas.drawCircle(cx - o * 1.1f, cy + o * 0.35f, dotR, gripDotPaint)
        canvas.drawCircle(cx + o * 0.4f, cy - o * 1.05f, dotR, gripDotPaint)
    }
}
