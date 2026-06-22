package fansirsqi.xposed.sesame.hook.simple

import android.content.Context
import android.graphics.*
import android.os.Looper
import fansirsqi.xposed.sesame.ml.Slider
import fansirsqi.xposed.sesame.util.GlobalThreadPools
import fansirsqi.xposed.sesame.util.Log
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.support.model.Model
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.*

class SliderTFLite(val context: Context) {

    companion object {
        private const val TAG = "SliderTFLite"
        private const val CONF_THRESHOLD = 0.5f
        private const val IOU_THRESHOLD = 0.8f
        private const val Y_IOU_THRESHOLD = 0.85f
        private const val INPUT_SIZE = 640
        private const val MASK_NUM = 32
        private const val NUM_ANCHORS = 8400
        private const val MODEL_IDLE_TIMEOUT_MS = 60 * 60 * 1000L

        private val sharedModelMutex = Mutex()

        @Volatile
        private var sharedModel: SliderTFLite? = null

        @Volatile
        private var lastUsedAt: Long = 0L

        @Volatile
        private var unloadTicket: Long = 0L

        // 识别结果缓存：bitmapHash → 结果（避免重试时重复识别同一张截图）
        private const val CACHE_MAX_SIZE = 3
        private val recognitionCache = object : LinkedHashMap<Int, SlideRecognitionResult?>(CACHE_MAX_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, SlideRecognitionResult?>?): Boolean = size > CACHE_MAX_SIZE
        }

        /** bitmap内容轻量hash（采样多行像素+宽高，避免滑动前后截图碰撞） */
        private fun bitmapHash(bitmap: Bitmap): Int {
            var h = bitmap.width * 31 + bitmap.height
            val stride = maxOf(1, bitmap.height / 4)
            for (row in 0 until minOf(4, bitmap.height)) {
                val y = row * stride
                val rowPixels = IntArray(minOf(200, bitmap.width))
                bitmap.getPixels(rowPixels, 0, rowPixels.size, 0, y, rowPixels.size, 1)
                for (p in rowPixels) h = h * 31 + p
            }
            return h
        }

        fun preloadAsync(context: Context) {
            val appContext = context.applicationContext
            GlobalThreadPools.execute(
                CoroutineName("SliderTFLitePreload") + GlobalThreadPools.computeDispatcher
            ) {
                val startTime = System.currentTimeMillis()
                Log.record(
                    TAG,
                    "[预加载开始] thread=${Thread.currentThread().name}, isMain=${isMainThread()}"
                )
                try {
                    obtainSharedModel(appContext, "preload")
                    Log.record(
                        TAG,
                        "[预加载结束] success=true, cost=${System.currentTimeMillis() - startTime}ms"
                    )
                } catch (e: Exception) {
                    Log.record(
                        TAG,
                        "[预加载结束] success=false, cost=${System.currentTimeMillis() - startTime}ms, error=${e.message}"
                    )
                    Log.printStackTrace(TAG, "模型预加载失败", e)
                }
            }
        }

        suspend fun identifyShared(
            context: Context,
            bitmap: Bitmap,
            conf: Float = CONF_THRESHOLD,
            iou: Float = IOU_THRESHOLD
        ): SlideRecognitionResult? {
            // 3. 识别结果缓存：同一bitmap避免重复推理（重试循环中getBitmapFromView可能返回相同截图）
            val hash = bitmapHash(bitmap)
            recognitionCache[hash]?.let { cached ->
                Log.record(TAG, "[缓存命中] hash=$hash, result=${if(cached==null)"null" else "found"}")
                return cached
            }

            var result: SlideRecognitionResult?
            // 1. 优先尝试 TFLite 模型识别
            try {
                val detector = obtainSharedModel(context.applicationContext, "inference")
                result = withContext(GlobalThreadPools.computeDispatcher) {
                    val startTime = System.currentTimeMillis()
                    try {
                        detector.identifySlideRecognition(bitmap, conf, iou)
                    } finally {
                        touchSharedModelLocked()
                        Log.record(TAG, "[模型推理结束] cost=${System.currentTimeMillis() - startTime}ms")
                    }
                }
                if (result == null) {
                    Log.record(TAG, "[模型识别为空] 降级为边缘检测")
                    result = identifyGapByEdgeDetection(bitmap)
                }
            } catch (e: Exception) {
                // 2. TFLite 不可用 → 降级边缘检测
                Log.record(TAG, "[模型不可用] 降级为边缘检测: ${e.message}")
                result = identifyGapByEdgeDetection(bitmap)
            }

            // 存入缓存
            recognitionCache[hash] = result
            return result
        }

        /**
         * 基于Sobel边缘检测的缺口定位（无需TFLite模型，纯像素计算）
         * 参考 captcha-recognizer 的 Canny 边缘检测原理
         * 原理：滑块缺口在垂直方向有明显边缘，通过列向梯度强度找到缺口位置
         */
        private fun identifyGapByEdgeDetection(bitmap: Bitmap): SlideRecognitionResult? {
            val w = bitmap.width
            val h = bitmap.height
            if (w < 20 || h < 20) return null

            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            // 1. 灰度转换
            val gray = FloatArray(w * h)
            for (i in pixels.indices) {
                val p = pixels[i]
                gray[i] = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
            }

            // 2. Sobel垂直边缘检测（列向梯度强度）
            val colEdge = FloatArray(w)
            for (x in 1 until w - 1) {
                var sum = 0f
                for (y in 1 until h - 1) {
                    // Sobel X kernel: [-1 0 1; -2 0 2; -1 0 1]
                    val gx = -gray[(y-1)*w + (x-1)] + gray[(y-1)*w + (x+1)] +
                             -2f*gray[y*w + (x-1)] + 2f*gray[y*w + (x+1)] +
                             -gray[(y+1)*w + (x-1)] + gray[(y+1)*w + (x+1)]
                    sum += Math.abs(gx)
                }
                colEdge[x] = sum / h
            }

            // 3. 滑动窗口平滑
            val win = (w * 0.05f).toInt().coerceIn(3, 20)
            val smoothed = FloatArray(w)
            for (x in 0 until w) {
                var sum = 0f; var cnt = 0
                for (dx in -win..win) {
                    val idx = x + dx
                    if (idx in 0 until w) { sum += colEdge[idx]; cnt++ }
                }
                smoothed[x] = sum / cnt
            }

            // 4. 基线与阈值
            var baselineSum = 0f
            for (x in 0 until w) baselineSum += smoothed[x]
            val baseline = baselineSum / w
            val threshold = baseline * 1.3f

            // 5. 从图像30%处开始搜索（避开左侧滑块区域）
            val searchStart = (w * 0.3f).toInt()
            var bestStart = 0; var bestEnd = 0; var bestLen = 0
            var regStart = -1
            var x = searchStart
            while (x < w) {
                if (smoothed[x] > threshold) {
                    if (regStart < 0) regStart = x
                } else {
                    if (regStart >= 0) {
                        val len = x - regStart
                        if (len > bestLen) { bestLen = len; bestStart = regStart; bestEnd = x }
                        regStart = -1
                    }
                }
                x++
            }
            if (regStart >= 0) {
                val len = w - regStart
                if (len > bestLen) { bestStart = regStart; bestEnd = w }
            }

            val gapCenterX: Float
            val confidence: Float

            if (bestLen >= 5) {
                gapCenterX = ((bestStart + bestEnd) / 2f).coerceIn(w * 0.25f, w * 0.88f)
                confidence = (smoothed[gapCenterX.toInt()] / (baseline + 1f)).coerceAtMost(0.7f)
            } else {
                // 无显著区域：取最大梯度列
                var maxIdx = searchStart; var maxVal = 0f
                for (xx in searchStart until w - win) {
                    val grad = smoothed[xx + 1] - smoothed[xx - 1]
                    if (grad > maxVal) { maxVal = grad; maxIdx = xx }
                }
                gapCenterX = maxIdx.coerceIn(searchStart, (w * 0.88f).toInt()).toFloat()
                confidence = (maxVal / (baseline + 1f)).coerceAtMost(0.5f)
            }

            Log.record(TAG, "[边缘检测] 缺口区域: ${bestStart}-${bestEnd}, 中心: ${gapCenterX.toInt()}, 置信度: ${"%.2f".format(confidence)}, 图: ${w}x${h}")

            return SlideRecognitionResult(
                sliderX = 0f, sliderY = h / 2f,
                targetX = gapCenterX, targetY = h / 2f,
                confidence = confidence, candidateCount = 1
            )
        }

        private suspend fun obtainSharedModel(context: Context, reason: String): SliderTFLite {
            return withContext(GlobalThreadPools.computeDispatcher) {
                sharedModelMutex.withLock {
                    sharedModel?.let { model ->
                        lastUsedAt = System.currentTimeMillis()
                        scheduleIdleReleaseLocked()
                        Log.record(
                            TAG,
                            "[复用全局模型实例] reason=$reason, thread=${Thread.currentThread().name}, isMain=${isMainThread()}"
                        )
                        return@withLock model
                    }

                    val initStartTime = System.currentTimeMillis()
                    Log.record(
                        TAG,
                        "[初始化开始] reason=$reason, thread=${Thread.currentThread().name}, isMain=${isMainThread()}"
                    )
                    val detector = SliderTFLite(context.applicationContext)
                    val initSuccess = detector.init()
                    if (!initSuccess) {
                        detector.close()
                        throw IllegalStateException("SliderTFLite init failed")
                    }
                    sharedModel = detector
                    lastUsedAt = System.currentTimeMillis()
                    scheduleIdleReleaseLocked()
                    Log.record(
                        TAG,
                        "[初始化结束] success=true, cost=${System.currentTimeMillis() - initStartTime}ms"
                    )
                    detector
                }
            }
        }

        private suspend fun touchSharedModelLocked() {
            sharedModelMutex.withLock {
                if (sharedModel != null) {
                    lastUsedAt = System.currentTimeMillis()
                    scheduleIdleReleaseLocked()
                }
            }
        }

        private fun scheduleIdleReleaseLocked() {
            val ticket = ++unloadTicket
            GlobalThreadPools.execute(
                CoroutineName("SliderTFLiteIdleRelease") + GlobalThreadPools.computeDispatcher
            ) {
                delay(MODEL_IDLE_TIMEOUT_MS)
                sharedModelMutex.withLock {
                    if (ticket != unloadTicket) {
                        return@withLock
                    }
                    val idleFor = System.currentTimeMillis() - lastUsedAt
                    if (sharedModel != null && idleFor >= MODEL_IDLE_TIMEOUT_MS) {
                        Log.record(
                            TAG,
                            "[模型空闲超时卸载] idleMs=$idleFor, thread=${Thread.currentThread().name}, isMain=${isMainThread()}"
                        )
                        sharedModel?.close()
                        sharedModel = null
                    }
                }
            }
        }

        private fun isMainThread(): Boolean {
            return Looper.getMainLooper().thread === Thread.currentThread()
        }
    }

    private var sliderModel: Slider? = null

    fun init(): Boolean {
        return initModel()
    }

    private fun initModel(): Boolean {
        try {
            val optionsBuilder = Model.Options.Builder()
            sliderModel = Slider.newInstance(context, optionsBuilder.build())
            Log.record(TAG, "模型初始化成功")
            return true
        } catch (e: IOException) {
            Log.record(TAG, "模型初始化失败: ${e.message}")
            Log.printStackTrace(TAG, "SliderTFLite 初始化异常", e)
            return false
        }
    }

    fun close() {
        sliderModel?.close()
        sliderModel = null
        Log.record(TAG, "模型资源已释放")
    }

    data class DetectionResult(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val score: Float,
        val classId: Int,
        val maskCoeffs: FloatArray,
        var mask: Bitmap? = null
    )

    data class SlideRecognitionResult(
        val sliderX: Float,
        val sliderY: Float,
        val targetX: Float,
        val targetY: Float,
        val confidence: Float,
        val candidateCount: Int
    )

    fun identifyOffset(
        bitmap: Bitmap,
        conf: Float = CONF_THRESHOLD,
        iou: Float = IOU_THRESHOLD
    ): Pair<Int, Float> {
        val result = identifySlideRecognition(bitmap, conf, iou)
        return if (result != null) {
            Pair(result.targetX.toInt(), result.confidence)
        } else {
            Pair(0, 0f)
        }
    }

    fun identifySlideRecognition(
        bitmap: Bitmap,
        conf: Float = CONF_THRESHOLD,
        iou: Float = IOU_THRESHOLD
    ): SlideRecognitionResult? {
        val results = predict(bitmap, conf, iou)

        Log.record(TAG, "识别候选框数量: ${results.size}")
        results.forEachIndexed { index, result ->
            Log.record(
                TAG,
                "候选[$index] box=(${result.x1.toInt()},${result.y1.toInt()},${result.x2.toInt()},${result.y2.toInt()}) score=${result.score}"
            )
        }

        if (results.isEmpty()) return null

        var targetBox: DetectionResult?
        var sliderBox: DetectionResult? = null

        if (results.size == 1) {
            val box = results[0]
            Log.record(TAG, "仅检测到1个框: (${box.x1.toInt()},${box.y1.toInt()}) score=${box.score}")
            return SlideRecognitionResult(
                sliderX = (box.x1 + box.x2) / 2f,
                sliderY = (box.y1 + box.y2) / 2f,
                targetX = (box.x1 + box.x2) / 2f,
                targetY = (box.y1 + box.y2) / 2f,
                confidence = box.score,
                candidateCount = 1
            )
        } else {
            val sliderIndex = results.indices.minByOrNull { results[it].x1 } ?: 0
            val slider = results[sliderIndex]
            sliderBox = slider
            Log.record(TAG, "判定滑块框: index=$sliderIndex center=(${(slider.x1+slider.x2)/2f},${(slider.y1+slider.y2)/2f}) score=${slider.score}")

            val candidates = results.filterIndexed { index, _ -> index != sliderIndex }

            if (candidates.isEmpty()) {
                targetBox = slider
            } else {
                val yFiltered = candidates.filter {
                    yIou(slider, it) > Y_IOU_THRESHOLD
                }

                val finalCandidates = if (yFiltered.isEmpty()) candidates else yFiltered

                if (finalCandidates.size == 1) {
                    targetBox = finalCandidates[0]
                } else {
                    var maxIou = -1f
                    var bestCandidate = finalCandidates[0]

                    val sliderMask = slider.mask ?: generateMask(slider)

                    for (candidate in finalCandidates) {
                        val candidateMask = candidate.mask ?: generateMask(candidate)
                        val shapeIou = calculateShapeIou(sliderMask, candidateMask)
                        if (shapeIou > maxIou) {
                            maxIou = shapeIou
                            bestCandidate = candidate
                        }
                    }
                    targetBox = bestCandidate
                }
            }
        }

        if (targetBox != null && sliderBox != null) {
            val sliderCenterX = (sliderBox.x1 + sliderBox.x2) / 2f
            val sliderCenterY = (sliderBox.y1 + sliderBox.y2) / 2f
            val targetCenterX = (targetBox.x1 + targetBox.x2) / 2f
            val targetCenterY = (targetBox.y1 + targetBox.y2) / 2f
            Log.record(TAG, "滑块中心: (${sliderCenterX.toInt()},${sliderCenterY.toInt()}), 目标中心: (${targetCenterX.toInt()},${targetCenterY.toInt()}), 距离: ${(targetCenterX-sliderCenterX).toInt()}")
            return SlideRecognitionResult(
                sliderX = sliderCenterX,
                sliderY = sliderCenterY,
                targetX = targetCenterX,
                targetY = targetCenterY,
                confidence = targetBox.score,
                candidateCount = results.size
            )
        }

        return null
    }

    private fun predict(
        img: Bitmap,
        confThreshold: Float,
        iouThreshold: Float
    ): List<DetectionResult> {
        val model = sliderModel ?: return emptyList()

        val (inputBitmap, ratio, padding) = letterbox(img)

        val inputFeature0 = TensorBuffer.createFixedSize(
            intArrayOf(1, INPUT_SIZE, INPUT_SIZE, 3),
            DataType.FLOAT32
        )

        loadBitmapToTensorBuffer(inputBitmap, inputFeature0)

        val outputs = model.process(inputFeature0)

        val predsFlat = outputs.outputFeature0AsTensorBuffer.floatArray
        val protosFlat = outputs.outputFeature1AsTensorBuffer.floatArray

        return postprocess(
            predsFlat, protosFlat,
            img.width, img.height, ratio, padding,
            confThreshold, iouThreshold
        )
    }

    private fun loadBitmapToTensorBuffer(bitmap: Bitmap, tensorBuffer: TensorBuffer) {
        val floatBuffer = tensorBuffer.buffer.order(ByteOrder.nativeOrder())
        floatBuffer.rewind()

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            floatBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            floatBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            floatBuffer.putFloat((pixel and 0xFF) / 255.0f)
        }
    }

    private fun postprocess(
        preds: FloatArray,
        protos: FloatArray,
        orgW: Int, orgH: Int,
        ratio: Float, padding: Pair<Int, Int>,
        confThreshold: Float, iouThreshold: Float
    ): List<DetectionResult> {
        val proposals = ArrayList<DetectionResult>()

        for (i in 0 until NUM_ANCHORS) {
            val scoreIdx = 4 * NUM_ANCHORS + i
            val score = preds[scoreIdx]

            if (score > confThreshold) {
                val cx = preds[0 * NUM_ANCHORS + i]
                val cy = preds[1 * NUM_ANCHORS + i]
                val w = preds[2 * NUM_ANCHORS + i]
                val h = preds[3 * NUM_ANCHORS + i]

                val x1 = cx - w / 2
                val y1 = cy - h / 2
                val x2 = cx + w / 2
                val y2 = cy + h / 2

                val maskCoeffs = FloatArray(MASK_NUM)
                for (j in 0 until MASK_NUM) {
                    maskCoeffs[j] = preds[(5 + j) * NUM_ANCHORS + i]
                }

                proposals.add(DetectionResult(x1, y1, x2, y2, score, 0, maskCoeffs))
            }
        }

        val nmsResults = nms(proposals, iouThreshold)
        val finalResults = ArrayList<DetectionResult>()

        for (res in nmsResults) {
            val rX1 = ((res.x1 - padding.first) / ratio).coerceIn(0f, orgW.toFloat())
            val rY1 = ((res.y1 - padding.second) / ratio).coerceIn(0f, orgH.toFloat())
            val rX2 = ((res.x2 - padding.first) / ratio).coerceIn(0f, orgW.toFloat())
            val rY2 = ((res.y2 - padding.second) / ratio).coerceIn(0f, orgH.toFloat())

            val mask = processMask(res.maskCoeffs, protos, res.x1, res.y1, res.x2, res.y2, 160, 160, INPUT_SIZE, INPUT_SIZE)
            val croppedMask = cropAndScaleMask(mask, 160, 160, res.x1, res.y1, res.x2, res.y2, ratio, padding, orgW, orgH, rX1, rY1, rX2 - rX1, rY2 - rY1)

            finalResults.add(DetectionResult(rX1, rY1, rX2, rY2, res.score, res.classId, res.maskCoeffs, croppedMask))
        }

        return finalResults
    }

    private fun processMask(coeffs: FloatArray, protos: FloatArray, boxX1: Float, boxY1: Float, boxX2: Float, boxY2: Float, protoH: Int, protoW: Int, imgH: Int, imgW: Int): BooleanArray {
        val mask = BooleanArray(protoH * protoW)
        val sx = protoW.toFloat() / imgW
        val sy = protoH.toFloat() / imgH
        val bx1 = (boxX1 * sx).toInt().coerceIn(0, protoW)
        val by1 = (boxY1 * sy).toInt().coerceIn(0, protoH)
        val bx2 = (boxX2 * sx).toInt().coerceIn(0, protoW)
        val by2 = (boxY2 * sy).toInt().coerceIn(0, protoH)
        for (y in by1 until by2) {
            val yOffset = y * protoW
            for (x in bx1 until bx2) {
                var sum = 0f
                val baseIdx = (yOffset + x) * MASK_NUM
                for (k in 0 until MASK_NUM) { sum += coeffs[k] * protos[baseIdx + k] }
                val prob = 1.0f / (1.0f + exp(-sum))
                if (prob > 0.5f) mask[yOffset + x] = true
            }
        }
        return mask
    }

    private fun cropAndScaleMask(rawMask: BooleanArray, rawW: Int, rawH: Int, lbX1: Float, lbY1: Float, lbX2: Float, lbY2: Float, ratio: Float, padding: Pair<Int, Int>, orgW: Int, orgH: Int, finalX: Float, finalY: Float, finalW: Float, finalH: Float): Bitmap {
        val w = max(1, finalW.toInt()); val h = max(1, finalH.toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        for (y in 0 until h) for (x in 0 until w) {
            val ox = finalX + x; val oy = finalY + y
            val lx = ox * ratio + padding.first; val ly = oy * ratio + padding.second
            val mx = (lx * (rawW.toFloat() / INPUT_SIZE)).toInt(); val my = (ly * (rawH.toFloat() / INPUT_SIZE)).toInt()
            if (mx in 0 until rawW && my in 0 until rawH && rawMask[my * rawW + mx]) bmp.setPixel(x, y, Color.WHITE)
        }
        return bmp
    }

    private fun generateMask(result: DetectionResult): Bitmap? = result.mask
    private fun yIou(box1: DetectionResult, box2: DetectionResult): Float {
        val intersection = max(0f, min(box1.y2, box2.y2) - max(box1.y1, box2.y1))
        val union = (box1.y2 - box1.y1) + (box2.y2 - box2.y1) - intersection
        return if (union != 0f) intersection / union else 0f
    }
    private fun calculateShapeIou(mask1: Bitmap?, mask2: Bitmap?): Float {
        if (mask1 == null || mask2 == null) return 0f
        val sMask1 = Bitmap.createScaledBitmap(mask1, 100, 100, true)
        val sMask2 = Bitmap.createScaledBitmap(mask2, 100, 100, true)
        var i=0; var u=0
        for (y in 0 until 100) for (x in 0 until 100) {
            val p1=sMask1.getPixel(x,y)!=0; val p2=sMask2.getPixel(x,y)!=0
            if(p1&&p2)i++; if(p1||p2)u++
        }
        return if(u>0) i.toFloat()/u else 0f
    }
    private fun nms(boxes: List<DetectionResult>, threshold: Float): List<DetectionResult> {
        val sorted = boxes.sortedByDescending { it.score }.toMutableList()
        val selected = ArrayList<DetectionResult>()
        while (sorted.isNotEmpty()) { val first = sorted.removeAt(0); selected.add(first); val it = sorted.iterator(); while (it.hasNext()) { if (iou(first, it.next()) >= threshold) it.remove() } }
        return selected
    }
    private fun iou(a: DetectionResult, b: DetectionResult): Float {
        val l=max(a.x1,b.x1); val r=min(a.x2,b.x2); val t=max(a.y1,b.y1); val btm=min(a.y2,b.y2)
        val iw=max(0f,r-l); val ih=max(0f,btm-t); val inter=iw*ih
        return inter / ((a.x2-a.x1)*(a.y2-a.y1) + (b.x2-b.x1)*(b.y2-b.y1) - inter)
    }
    private fun letterbox(img: Bitmap): Triple<Bitmap, Float, Pair<Int, Int>> {
        val r = min(INPUT_SIZE.toFloat() / img.width, INPUT_SIZE.toFloat() / img.height)
        val nw = (img.width * r).roundToInt(); val nh = (img.height * r).roundToInt()
        val dw = (INPUT_SIZE - nw) / 2; val dh = (INPUT_SIZE - nh) / 2
        val resized = Bitmap.createScaledBitmap(img, nw, nh, true)
        // 使用RGB_565节省50%内存（灰色边框不需要Alpha通道）
        val result = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.RGB_565)
        Canvas(result).apply { drawColor(Color.rgb(114, 114, 114)); drawBitmap(resized, dw.toFloat(), dh.toFloat(), null) }
        if (!resized.isRecycled && resized !== img) resized.recycle()
        return Triple(result, r, Pair(dw, dh))
    }
}
