package com.shijing.xomniclaw.agent.tools.device.yolo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer

/**
 * 本地 ONNX Runtime 检测器。
 *
 * 该类只负责“给一张 Bitmap，返回原图坐标系下的检测框”。
 */
class UiYoloOnnxDetector(
    context: Context,
    private val assetModelFileName: String = UiYoloClassLabels.MODEL_ASSET_NAME,
    private val inputSize: Int = UiYoloClassLabels.INPUT_SIZE,
    initialConf: Float = UiYoloClassLabels.DEFAULT_CONFIDENCE,
    initialIou: Float = UiYoloClassLabels.DEFAULT_IOU
) : AutoCloseable {

    @Volatile
    var confThreshold: Float = initialConf.coerceIn(0.001f, 0.999f)
        set(value) {
            field = value.coerceIn(0.001f, 0.999f)
        }

    @Volatile
    var iouThreshold: Float = initialIou.coerceIn(0.05f, 0.95f)
        set(value) {
            field = value.coerceIn(0.05f, 0.95f)
        }

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelBytes = context.assets.open(assetModelFileName).readBytes()
        session = environment.createSession(
            modelBytes,
            OrtSession.SessionOptions().apply {
                // snapshot 时会与 UI 树一起执行，线程数保持保守，减少对主链路的干扰。
                setIntraOpNumThreads(4)
                setInterOpNumThreads(1)
            }
        )
    }

    data class Detection(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val score: Float,
        val classId: Int
    )

    data class Result(
        val detections: List<Detection>,
        val classCount: Int,
        val timing: UiDetectionTiming
    )

    fun detect(bitmap: Bitmap): Result {
        val startMs = System.currentTimeMillis()
        val (letterboxedBitmap, letterboxParams) = UiYoloPreprocess.letterbox(bitmap, inputSize)
        val inputTensorData = UiYoloPreprocess.bitmapToNchw01(letterboxedBitmap, inputSize)
        if (!letterboxedBitmap.isRecycled) {
            letterboxedBitmap.recycle()
        }
        val preprocessEndMs = System.currentTimeMillis()

        val inputName = session.inputNames.iterator().next()
        val inputShape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        lateinit var rawOutput: FloatArray
        lateinit var fixedShape: LongArray

        val inferenceEndMs = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(inputTensorData),
            inputShape
        ).use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { outputs ->
                val finishedMs = System.currentTimeMillis()
                val (output, outputShape) = extractOutputToFloatArray(outputs[0].value)
                rawOutput = output
                fixedShape = fixOutputShapeIfNeeded(outputShape, rawOutput)
                finishedMs
            }
        }

        val letterboxSize = letterboxParams.dstSize.toFloat()
        val decoded = UiYoloPostprocess.decodeRaw(
            raw = rawOutput,
            shape = fixedShape,
            confThresh = confThreshold,
            useSigmoidOnClass = true,
            anchorMajor = false
        ).map { detection ->
            UiYoloPostprocess.RawDetection(
                x1 = scaleIfNormalized(detection.x1, letterboxSize),
                y1 = scaleIfNormalized(detection.y1, letterboxSize),
                x2 = scaleIfNormalized(detection.x2, letterboxSize),
                y2 = scaleIfNormalized(detection.y2, letterboxSize),
                score = detection.score,
                classId = detection.classId
            )
        }

        val nmsDetections = UiYoloPostprocess.nmsXyxy(
            detections = decoded,
            iouThresh = iouThreshold,
            maxDet = UiYoloClassLabels.MAX_DETECTIONS
        )
        val classCount = fixedShape[1].toInt() - 4
        val mappedDetections = nmsDetections.map { detection ->
            val scaled = UiYoloPostprocess.scaleBoxesXyxy(
                floatArrayOf(detection.x1, detection.y1, detection.x2, detection.y2),
                letterboxParams
            )
            Detection(
                x1 = scaled[0],
                y1 = scaled[1],
                x2 = scaled[2],
                y2 = scaled[3],
                score = detection.score,
                classId = detection.classId
            )
        }
        val endMs = System.currentTimeMillis()

        return Result(
            detections = mappedDetections,
            classCount = classCount,
            timing = UiDetectionTiming(
                preprocessMs = preprocessEndMs - startMs,
                inferenceMs = inferenceEndMs - preprocessEndMs,
                postprocessMs = endMs - inferenceEndMs
            )
        )
    }

    override fun close() {
        session.close()
        environment.close()
    }
}

private fun scaleIfNormalized(value: Float, letterboxSize: Float): Float {
    return if (value in 0f..1.01f) value * letterboxSize else value
}

private fun fixOutputShapeIfNeeded(shape: LongArray, raw: FloatArray): LongArray {
    if (shape.size != 3) return shape
    val secondDim = shape[1].toInt()
    val thirdDim = shape[2].toInt()
    val channelLike = secondDim - 4
    val anchorLike = thirdDim - 4

    // 标准输出 [1, 4+nc, anchors] 时直接返回。
    if (channelLike > 0 && channelLike <= 512) return shape

    // 若输出为 [1, anchors, 4+nc]，先转置后再按 channel-major 解码。
    if (anchorLike > 0 && anchorLike <= 512) {
        transposeLastTwoDims(raw, secondDim, 4 + anchorLike)
        return longArrayOf(1, 4 + anchorLike.toLong(), secondDim.toLong())
    }
    return shape
}

private fun transposeLastTwoDims(raw: FloatArray, anchorCount: Int, channels: Int) {
    val copy = raw.copyOf()
    for (anchor in 0 until anchorCount) {
        for (channel in 0 until channels) {
            raw[channel * anchorCount + anchor] = copy[anchor * channels + channel]
        }
    }
}

private fun extractOutputToFloatArray(value: Any): Pair<FloatArray, LongArray> {
    return when (value) {
        is OnnxTensor -> onnxTensorToFloatArray(value) to value.info.shape
        else -> flattenNestedFloatTensor(value)
    }
}

private fun flattenNestedFloatTensor(value: Any): Pair<FloatArray, LongArray> {
    val dimensions = nestedFloatTensorDimensions(value)
    val buffer = ArrayList<Float>(dimensions.fold(1, Int::times))
    flattenNestedFloatTensorData(value, buffer)
    return buffer.toFloatArray() to dimensions.map { it.toLong() }.toLongArray()
}

private fun nestedFloatTensorDimensions(value: Any): List<Int> {
    return when (value) {
        is FloatArray -> listOf(value.size)
        is Array<*> -> {
            require(value.isNotEmpty()) { "ONNX 输出为空数组" }
            listOf(value.size) + nestedFloatTensorDimensions(value[0]!!)
        }
        else -> throw IllegalArgumentException("不支持的 ONNX 输出类型: ${value.javaClass.name}")
    }
}

private fun flattenNestedFloatTensorData(value: Any, output: MutableList<Float>) {
    when (value) {
        is FloatArray -> value.forEach { output += it }
        is Array<*> -> value.forEach { flattenNestedFloatTensorData(it!!, output) }
        else -> throw IllegalArgumentException("展平 ONNX 输出失败: ${value.javaClass.name}")
    }
}

private fun onnxTensorToFloatArray(tensor: OnnxTensor): FloatArray {
    val total = tensor.info.shape.fold(1L) { acc, value -> acc * value }
    val result = FloatArray(total.toInt())
    val buffer = tensor.getFloatBuffer()
    buffer.rewind()
    val readable = minOf(result.size, buffer.remaining())
    buffer.get(result, 0, readable)
    return result
}
