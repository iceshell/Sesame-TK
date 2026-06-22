package fansirsqi.xposed.sesame.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import fansirsqi.xposed.sesame.hook.simple.MotionEventSimulator
import fansirsqi.xposed.sesame.hook.simple.SimplePageManager
import fansirsqi.xposed.sesame.hook.simple.SimplePageManager.ActivityHandleResult
import fansirsqi.xposed.sesame.hook.simple.SimpleViewImage
import fansirsqi.xposed.sesame.hook.simple.ViewHierarchyAnalyzer
import fansirsqi.xposed.sesame.hook.simple.SliderTFLite
import fansirsqi.xposed.sesame.util.CommandUtil
import fansirsqi.xposed.sesame.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.io.ByteArrayOutputStream
import kotlin.random.Random

import fansirsqi.xposed.sesame.hook.VersionHook
import fansirsqi.xposed.sesame.entity.AlipayVersion

data class SlideCoordinates(val startX: Float, val startY: Float, val endX: Float, val endY: Float)
private data class CaptchaVisualSnapshot(val fullBitmap: Bitmap, val croppedBitmap: Bitmap, val recognitionResult: SliderTFLite.SlideRecognitionResult?)
private data class SliderHandleDetection(val centerX: Float, val centerY: Float, val left: Int, val top: Int, val right: Int, val bottom: Int, val pixelCount: Int)
private data class CaptchaPreCheckResult(val passed: Boolean, val passReasons: List<String>, val failReasons: List<String>, val sliderHandle: SliderHandleDetection?)
private data class LightweightCaptchaPreCheckResult(val passed: Boolean, val passReasons: List<String>, val failReasons: List<String>, val sliderHandle: SliderHandleDetection?, val fullBitmap: Bitmap?, val croppedBitmap: Bitmap?, val cropTop: Int, val cropBottom: Int)

abstract class BaseCaptchaHandler {
    companion object {
        private const val TAG = "CaptchaHandler"
        private const val SLIDE_START_OFFSET = 25; private const val SLIDE_END_MARGIN = 20
        private const val SLIDE_DURATION_MIN = 900L; private const val SLIDE_DURATION_MAX = 1400L
        private const val CORRECTIVE_SLIDE_DURATION_MIN = 420L; private const val CORRECTIVE_SLIDE_DURATION_MAX = 650L
        private const val POST_SLIDE_CHECK_DELAY_MS = 1200L; private const val NEW_CAPTCHA_CONFIDENCE_THRESHOLD = 0.55f
        private const val RENDER_WAIT_MAX_MS = 1200L; private const val RENDER_POLL_INTERVAL_MS = 200L
        private const val OLD_SLIDE_VERIFY_TEXT_XPATH = "//TextView[contains(@text,'向右滑动验证')]"
        private const val NEW_SLIDE_VERIFY_TEXT_XPATH = "//View[contains(@text,'请拖动滑块完成拼图')]"
        private val captchaProcessingMutex = Mutex()
    }
    protected abstract fun getSlidePathKey(): String

    open suspend fun handleActivity(activity: Activity, root: SimpleViewImage): ActivityHandleResult {
        val startTime = System.currentTimeMillis()
        return try {
            Log.record(TAG, "[触发命中] Activity=${activity.javaClass.name}, isMain=${isMainThread()}")
            val isNewVersion = if (VersionHook.hasVersion()) { VersionHook.getCapturedVersion()?.let { it.compareTo(AlipayVersion("10.6.58.9999")) > 0 } ?: false } else false
            val result = if (isNewVersion) { Log.record(TAG, "[新版本] 图像识别模式"); handleNewVersionCaptcha(activity) }
                         else { Log.record(TAG, "[旧版本] 传统模式(stub)"); handleLegacySlideCaptcha(activity) }
            Log.record(TAG, "[完成] 耗时=${System.currentTimeMillis()-startTime}ms, 结果=$result")
            result
        } catch (e: Exception) { Log.record(TAG, "[异常] 耗时=${System.currentTimeMillis()-startTime}ms, type=${e.javaClass.simpleName}, msg=${e.message}"); Log.error(TAG, "验证码处理异常: ${e.stackTraceToString()}"); ActivityHandleResult.FAILED_RETRYABLE }
    }

    // 错误分级日志
    private fun logFail(level: String, reason: String, detail: String = "") {
        val msg = "[$level] $reason${if(detail.isNotEmpty()) " | $detail" else ""}"
        when (level) {
            "WARN" -> Log.record(TAG, msg)
            "ERROR" -> Log.error(TAG, msg)
            else -> Log.record(TAG, msg)
        }
    }
    private fun logPrecheckSkip(skipReason: String, failReasons: List<String>, passReasons: List<String>) {
        Log.record(TAG, "[预检跳过] reason=$skipReason; fail=${failReasons.joinToString("; ")}; pass=${passReasons.joinToString(", ")}")
    }
    private fun logAcceptedAfterSkip(anchorReason: String) { Log.record(TAG, "[放行接受] reason=$anchorReason") }
    private fun logRetryableFailure(reason: String) { Log.record(TAG, "[重试失败] reason=$reason") }

    @SuppressLint("SuspiciousIndentation")
    private suspend fun handleNewVersionCaptcha(activity: Activity): ActivityHandleResult {
        var processingWindowAcquired = false
        try {
            Log.record(TAG, "[新验证码] 开始处理, thread=${Thread.currentThread().name}, isMain=${isMainThread()}")
            val textAnchor = SimplePageManager.tryGetTopView(NEW_SLIDE_VERIFY_TEXT_XPATH)
            val anchorText = textAnchor?.getText()?.take(24)?.toString()?.takeIf { it.isNotBlank() }
            val hasTextAnchor = !anchorText.isNullOrBlank()
            if (hasTextAnchor) Log.record(TAG, "[前置命中] text-anchor=$anchorText") else Log.record(TAG, "[前置提示] text-anchor-missing, fallback=visual-precheck")
            val context = SimplePageManager.getContext(); if (context != null) { CommandUtil.connect(context); Log.record(TAG, "已发起 CommandService 预连接") }
            delay(1200L); val decorView = activity.window.decorView
            val lightweightPreCheck = evaluateLightweightCaptchaPreCheck(decorView, anchorText)
            if (!lightweightPreCheck.passed && hasTextAnchor) { logPrecheckSkip("normal-page-skip", lightweightPreCheck.failReasons, lightweightPreCheck.passReasons); return ActivityHandleResult.SKIP_NON_RETRYABLE }
            if (!lightweightPreCheck.passed && !hasTextAnchor) { Log.record(TAG, "[前置放行] blueHandle缺失, 继续识别流程") }
            if (!hasTextAnchor) Log.record(TAG, "[前置放行] reason=text-anchor-missing but visual-precheck-pass; pass=${lightweightPreCheck.passReasons.joinToString(", ")}")
            if (!captchaProcessingMutex.tryLock()) { logRetryableFailure("captcha-processing-window-busy"); return ActivityHandleResult.FAILED_RETRYABLE }
            processingWindowAcquired = true
            logAcceptedAfterSkip(if(hasTextAnchor)"text-anchor-present and precheck-pass" else "text-anchor-missing but visual-precheck-pass")
            val fullBitmap = lightweightPreCheck.fullBitmap ?: run { logRetryableFailure("lightweight-precheck-missing-fullBitmap"); return ActivityHandleResult.FAILED_RETRYABLE }
            val croppedBitmap = lightweightPreCheck.croppedBitmap ?: run { logRetryableFailure("lightweight-precheck-missing-croppedBitmap"); return ActivityHandleResult.FAILED_RETRYABLE }
            val cropTop = lightweightPreCheck.cropTop; val cropBottom = lightweightPreCheck.cropBottom
            val detectedHandle = lightweightPreCheck.sliderHandle ?: run { Log.record(TAG, "轻量前置检测未命中滑块手柄, 使用默认位置"); SliderHandleDetection(SLIDE_START_OFFSET.toFloat() * 3, (lightweightPreCheck.cropTop + lightweightPreCheck.cropBottom) / 2f, SLIDE_START_OFFSET, lightweightPreCheck.cropTop, SLIDE_START_OFFSET * 4, lightweightPreCheck.cropBottom, 0) }
            Log.record(TAG, "[模型转后台] callerThread=${Thread.currentThread().name}, isMain=${isMainThread()}")
            val recognitionResult = SliderTFLite.identifyShared(activity.applicationContext, croppedBitmap) ?: run { Log.record(TAG, "裁剪区域模型识别失败"); logRetryableFailure("model-recognition-null"); return ActivityHandleResult.FAILED_RETRYABLE }
            val sliderLocalX = recognitionResult.sliderX; val sliderLocalY = recognitionResult.sliderY + cropTop
            val targetLocalX = recognitionResult.targetX; val targetLocalY = recognitionResult.targetY + cropTop
            Log.record(TAG, "裁剪识别成功: 裁剪内坐标 滑块=(${recognitionResult.sliderX.toInt()},${recognitionResult.sliderY.toInt()}) 目标=(${recognitionResult.targetX.toInt()},${recognitionResult.targetY.toInt()})")
            val distance = targetLocalX - sliderLocalX
            val preCheck = evaluateNewCaptchaPreCheck(croppedBitmap, recognitionResult, anchorText, detectedHandle)
            if (!preCheck.passed && hasTextAnchor) { logPrecheckSkip("normal-page-skip", preCheck.failReasons, preCheck.passReasons); return ActivityHandleResult.SKIP_NON_RETRYABLE }
            if (!preCheck.passed && !hasTextAnchor) { Log.record(TAG, "[前置放行] 识别质量偏低(边缘检测), 继续尝试滑动") }
            Log.record(TAG, "[前置检测通过] reason=${if(hasTextAnchor)"captcha-precheck-pass" else "text-anchor-missing but visual-precheck-pass"}; 判定为滑块验证码页: ${preCheck.passReasons.joinToString(", ")}")
            val sliderHandle = preCheck.sliderHandle ?: detectedHandle
            val actualStartX = sliderHandle.centerX; val actualStartY = sliderHandle.centerY
            // 边缘检测sliderX≈0时，直接以缺口位置为终点；TFLite时用偏移量
            // 边缘检测不可靠时(sliderX≈0 或 slider≈target)，直接以缺口位置为终点
            val actualEndX = if (sliderLocalX < 10f || distance < 10f) {
                targetLocalX.coerceAtMost(actualStartX + targetLocalX.coerceAtLeast(50f))
            } else {
                actualStartX + distance
            }; val actualEndY = actualStartY
            Log.record(TAG, "命中滑块手柄: bounds=(${sliderHandle.left},${sliderHandle.top},${sliderHandle.right},${sliderHandle.bottom}), center=(${sliderHandle.centerX.toInt()},${sliderHandle.centerY.toInt()})")
            Log.record(TAG, "实际滑动参数: 起点=(${actualStartX.toInt()},${actualStartY.toInt()}), 终点=(${actualEndX.toInt()},${actualEndY.toInt()}), 距离=${distance.toInt()}px")
            val beforeSnapshot = CaptchaVisualSnapshot(fullBitmap, croppedBitmap, recognitionResult)
            return if (executeSlideOnView(decorView, actualStartX, actualStartY, actualEndX, actualEndY, beforeSnapshot, cropTop, cropBottom)) ActivityHandleResult.HANDLED
                   else { logRetryableFailure("execute-slide-on-view-failed"); ActivityHandleResult.FAILED_RETRYABLE }
        } catch (e: Exception) { Log.record(TAG, "新版验证码处理出错: ${e.stackTraceToString()}"); logRetryableFailure("new-version-exception"); return ActivityHandleResult.FAILED_RETRYABLE }
        finally { if (processingWindowAcquired) captchaProcessingMutex.unlock() }
    }

    private suspend fun executeSlideOnView(view: View, localStartX: Float, localStartY: Float, localEndX: Float, localEndY: Float, beforeSnapshot: CaptchaVisualSnapshot, cropTop: Int, cropBottom: Int): Boolean {
        val vloc = IntArray(2); view.getLocationOnScreen(vloc)
        val screenStartX = localStartX + vloc[0]; val screenStartY = localStartY + vloc[1]; val screenEndX = localEndX + vloc[0]; val screenEndY = localEndY + vloc[1]
        val dur = Random.nextLong(SLIDE_DURATION_MIN, SLIDE_DURATION_MAX + 1)
        Log.record(TAG, "执行滑动(全屏模式): 局部(${localStartX.toInt()},${localStartY.toInt()})->(${localEndX.toInt()},${localEndY.toInt()}), 屏幕(${screenStartX.toInt()},${screenStartY.toInt()})->(${screenEndX.toInt()},${screenEndY.toInt()}), 时长: ${dur}ms")
        MotionEventSimulator.simulateSwipe(view, screenStartX, screenStartY, screenEndX, screenEndY, dur)
        delay(POST_SLIDE_CHECK_DELAY_MS)
        Log.record(TAG, "[截图复核] 滑动后开始截图验证...")
        var result = verifyCaptchaSolvedByScreenshotOnce(view, beforeSnapshot, cropTop, cropBottom)
        if (!result) { Log.record(TAG, "[截图复核] 首次验证失败，尝试校正滑动..."); result = attemptCorrectiveSwipeIfNeeded(view, cropTop, cropBottom) }
        return result
    }

    private suspend fun verifyCaptchaSolvedByScreenshotOnce(view: View, beforeSnapshot: CaptchaVisualSnapshot, cropTop: Int, cropBottom: Int): Boolean {
        val afterFullBitmap = getBitmapFromView(view) ?: run { Log.record(TAG, "滑动后截图失败"); return false }
        saveDebugBitmap(afterFullBitmap, "post_slide_full_decorview")
        val sct = cropTop.coerceIn(0, (afterFullBitmap.height - 1).coerceAtLeast(0)); val scb = cropBottom.coerceIn(sct + 1, afterFullBitmap.height)
        val afterCroppedBitmap = Bitmap.createBitmap(afterFullBitmap, 0, sct, afterFullBitmap.width, scb - sct)
        saveDebugBitmap(afterCroppedBitmap, "post_slide_cropped_captcha_area")
        val afterRecognition = try { SliderTFLite.identifyShared(view.context.applicationContext, afterCroppedBitmap) } catch (e: Exception) { Log.record(TAG, "滑动后二次识别异常: ${e.message}"); null }
        val beforeRecognition = beforeSnapshot.recognitionResult
        val diffRatio = calculateBitmapDifferenceRatio(beforeSnapshot.croppedBitmap, afterCroppedBitmap)
        Log.record(TAG, "截图校验: diffRatio=$diffRatio, beforeRecognition=${formatRecognition(beforeRecognition)}, afterRecognition=${formatRecognition(afterRecognition)}")
        // 页面变化超过50%：验证码已通过（页面跳转）
        if (diffRatio > 0.5f) { Log.record(TAG, "截图校验通过：页面变化显著(diffRatio=$diffRatio)"); return true }
        if (afterRecognition != null && !isSolvedResidualDetection(beforeRecognition, afterRecognition, afterCroppedBitmap)) { Log.record(TAG, "截图校验失败：滑动后仍可识别到滑块/缺口"); return false }
        if (diffRatio < 0.015f) { Log.record(TAG, "截图校验失败：滑动前后画面变化过小"); return false }
        Log.record(TAG, "截图校验通过"); return true
    }

    private suspend fun attemptCorrectiveSwipeIfNeeded(view: View, cropTop: Int, cropBottom: Int): Boolean {
        val probeFullBitmap = getBitmapFromView(view) ?: return false
        saveDebugBitmap(probeFullBitmap, "correction_probe_full_decorview")
        val sct = cropTop.coerceIn(0, (probeFullBitmap.height - 1).coerceAtLeast(0)); val scb = cropBottom.coerceIn(sct + 1, probeFullBitmap.height)
        val probeCroppedBitmap = Bitmap.createBitmap(probeFullBitmap, 0, sct, probeFullBitmap.width, scb - sct)
        val probeRecognition = try { SliderTFLite.identifyShared(view.context.applicationContext, probeCroppedBitmap) } catch (e: Exception) { null } ?: run { Log.record(TAG, "校正探测未识别到可继续修正的目标"); return false }
        val sliderHandle = detectSliderHandle(probeFullBitmap, cropTop, probeRecognition) ?: run { Log.record(TAG, "校正探测未定位到滑块手柄"); return false }
        val correctionDistance = estimateCorrectionDistance(probeRecognition)
        if (kotlin.math.abs(correctionDistance) !in 4f..36f) { Log.record(TAG, "校正距离超出允许范围: $correctionDistance"); return false }
        val correctionEndX = sliderHandle.centerX + correctionDistance; val correctionEndY = sliderHandle.centerY
        val correctionDuration = Random.nextLong(CORRECTIVE_SLIDE_DURATION_MIN, CORRECTIVE_SLIDE_DURATION_MAX)
        Log.record(TAG, "[校正滑动] handle=(${sliderHandle.centerX.toInt()},${sliderHandle.centerY.toInt()}), correctionDistance=${correctionDistance.toInt()}")
        MotionEventSimulator.simulateSwipe(view, sliderHandle.centerX, sliderHandle.centerY, correctionEndX, correctionEndY, correctionDuration)
        delay(700L)
        return verifyCaptchaSolvedByScreenshotOnce(view, CaptchaVisualSnapshot(probeFullBitmap, probeCroppedBitmap, probeRecognition), cropTop, cropBottom)
    }

    private fun detectSliderHandle(fullBitmap: Bitmap, cropTop: Int, recognitionResult: SliderTFLite.SlideRecognitionResult?): SliderHandleDetection? {
        val searchTop = if (recognitionResult != null) (cropTop + recognitionResult.sliderY + 120f).toInt().coerceIn(0, fullBitmap.height - 1) else maxOf(cropTop + 40, fullBitmap.height * 55 / 100).coerceIn(0, fullBitmap.height - 1)
        val searchBottom = (fullBitmap.height - 140).coerceAtLeast(searchTop + 1).coerceAtMost(fullBitmap.height)
        val searchRight = (fullBitmap.width * 45 / 100).coerceAtMost(fullBitmap.width)
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1; var pixelCount = 0
        var y = searchTop; while (y < searchBottom) { var x = 0; while (x < searchRight) { if (isLikelySliderHandleBlue(fullBitmap.getPixel(x, y))) { if (x < minX) minX = x; if (y < minY) minY = y; if (x > maxX) maxX = x; if (y > maxY) maxY = y; pixelCount++ }; x++ }; y++ }
        if (pixelCount < 1200 || maxX <= minX || maxY <= minY) { Log.record(TAG, "滑块手柄检测失败: pixelCount=$pixelCount"); return null }
        val width = maxX - minX; val height = maxY - minY
        if (width !in 60..220 || height !in 60..220) { Log.record(TAG, "滑块手柄检测失败: boundsSize=${width}x$height, pixelCount=$pixelCount"); return null }
        val aspectRatio = width.toFloat() / height.toFloat()
        if (aspectRatio !in 0.75f..1.35f) { Log.record(TAG, "滑块手柄检测失败: aspectRatio=$aspectRatio, boundsSize=${width}x$height, pixelCount=$pixelCount"); return null }
        return SliderHandleDetection((minX + maxX) / 2f, (minY + maxY) / 2f, minX, minY, maxX, maxY, pixelCount)
    }

    private fun buildCaptchaCropBounds(fullBitmap: Bitmap, sliderHandle: SliderHandleDetection?): Pair<Int, Int> {
        if (sliderHandle == null) { val ft = (fullBitmap.height * 28 / 100).coerceIn(0, fullBitmap.height - 1); val fb = (fullBitmap.height * 88 / 100).coerceIn(ft + 1, fullBitmap.height); return ft to fb }
        val ct = maxOf(0, sliderHandle.top - (fullBitmap.height * 42 / 100))
        val cb = minOf(fullBitmap.height, sliderHandle.bottom + (fullBitmap.height * 8 / 100))
        return if (cb - ct >= fullBitmap.height * 22 / 100) ct to cb else { val ft = (fullBitmap.height * 28 / 100).coerceIn(0, fullBitmap.height - 1); val fb2 = (fullBitmap.height * 88 / 100).coerceIn(ft + 1, fullBitmap.height); ft to fb2 }
    }

    private fun evaluateLightweightCaptchaPreCheck(decorView: View, anchorText: String?): LightweightCaptchaPreCheckResult {
        val passReasons = mutableListOf<String>(); val failReasons = mutableListOf<String>(); passReasons += formatAnchorReason(anchorText)
        val fullBitmap = getBitmapFromView(decorView) ?: return LightweightCaptchaPreCheckResult(false, passReasons, failReasons, null, null, null, 0, 0)
        saveDebugBitmap(fullBitmap, "full_decorview")
        val sliderHandle = detectSliderHandle(fullBitmap, fullBitmap.height * 40 / 100, null)
        if (sliderHandle != null) passReasons += "blueHandle=(${sliderHandle.left},${sliderHandle.top},${sliderHandle.right},${sliderHandle.bottom})" else failReasons += "blueHandle-missing"
        val cropBounds = buildCaptchaCropBounds(fullBitmap, sliderHandle); val cropTop = cropBounds.first; val cropBottom = cropBounds.second
        val croppedBitmap = Bitmap.createBitmap(fullBitmap, 0, cropTop, fullBitmap.width, cropBottom - cropTop)
        Log.record(TAG, "[前置检测] 裁剪区域: top=$cropTop, bottom=$cropBottom, size=${croppedBitmap.width}x${croppedBitmap.height}")
        saveDebugBitmap(croppedBitmap, "cropped_captcha_area")
        sliderHandle?.let { val hw = it.right - it.left; val hh = it.bottom - it.top; val hxr = it.centerX / fullBitmap.width.toFloat(); if (hxr <= 0.42f) passReasons += "handleXRatio=${"%.2f".format(hxr)}" else failReasons += "handleXRatio=${"%.2f".format(hxr)}>0.42"; val hyr = it.centerY / fullBitmap.height.toFloat(); if (hyr in 0.55f..0.95f) passReasons += "handleYRatio=${"%.2f".format(hyr)}" else failReasons += "handleYRatio=${"%.2f".format(hyr)} out-of-range"; if (anchorText.isNullOrBlank()) { if (it.pixelCount >= 1400) passReasons += "handlePixels=${it.pixelCount}" else failReasons += "handlePixels=${it.pixelCount}<1400"; if (hw in 68..190 && hh in 68..190) passReasons += "handleSize=${hw}x${hh}" else failReasons += "handleSize=${hw}x$hh out-of-range" } }
        return LightweightCaptchaPreCheckResult(failReasons.isEmpty(), passReasons, failReasons, sliderHandle, fullBitmap, croppedBitmap, cropTop, cropBottom)
    }

    private fun evaluateNewCaptchaPreCheck(croppedBitmap: Bitmap, recognitionResult: SliderTFLite.SlideRecognitionResult, anchorText: String?, sliderHandle: SliderHandleDetection): CaptchaPreCheckResult {
        val passReasons = mutableListOf<String>(); val failReasons = mutableListOf<String>()
        passReasons += formatAnchorReason(anchorText); passReasons += "blueHandle=(${sliderHandle.left},${sliderHandle.top},${sliderHandle.right},${sliderHandle.bottom})"
        if (recognitionResult.candidateCount >= 2) passReasons += "candidateCount=${recognitionResult.candidateCount}" else failReasons += "candidateCount=${recognitionResult.candidateCount}<2"
        if (recognitionResult.confidence >= NEW_CAPTCHA_CONFIDENCE_THRESHOLD) passReasons += "confidence=${recognitionResult.confidence}" else failReasons += "confidence=${recognitionResult.confidence}<${NEW_CAPTCHA_CONFIDENCE_THRESHOLD}"
        val distance = recognitionResult.targetX - recognitionResult.sliderX; val minDistance = maxOf(croppedBitmap.width * 0.05f, 40f); val maxDistance = croppedBitmap.width * 0.82f
        if (distance in minDistance..maxDistance) passReasons += "distance=${distance.toInt()}" else failReasons += "distance=${distance.toInt()} not in ${minDistance.toInt()}..${maxDistance.toInt()}"
        val verticalDelta = kotlin.math.abs(recognitionResult.targetY - recognitionResult.sliderY); val maxVerticalDelta = maxOf(croppedBitmap.height * 0.12f, 72f)
        if (verticalDelta <= maxVerticalDelta) passReasons += "verticalDelta=${verticalDelta.toInt()}" else failReasons += "verticalDelta=${verticalDelta.toInt()}>${maxVerticalDelta.toInt()}"
        val sliderRatio = recognitionResult.sliderX / croppedBitmap.width.toFloat(); if (sliderRatio <= 0.35f) passReasons += "sliderRatio=${"%.2f".format(sliderRatio)}" else failReasons += "sliderRatio=${"%.2f".format(sliderRatio)}>0.35"
        val targetRatio = recognitionResult.targetX / croppedBitmap.width.toFloat(); if (targetRatio in 0.20f..0.95f) passReasons += "targetRatio=${"%.2f".format(targetRatio)}" else failReasons += "targetRatio=${"%.2f".format(targetRatio)} out-of-range"
        return CaptchaPreCheckResult(failReasons.isEmpty(), passReasons, failReasons, sliderHandle)
    }

    private fun calculateBitmapDifferenceRatio(before: Bitmap, after: Bitmap): Float {
        if (before.width != after.width || before.height != after.height) return 1f
        val sampleStepX = maxOf(1, before.width / 48); val sampleStepY = maxOf(1, before.height / 48); var totalDiff = 0L; var sampleCount = 0
        var y = 0; while (y < before.height) { var x = 0; while (x < before.width) { totalDiff += kotlin.math.abs(android.graphics.Color.red(before.getPixel(x, y)) - android.graphics.Color.red(after.getPixel(x, y))); totalDiff += kotlin.math.abs(android.graphics.Color.green(before.getPixel(x, y)) - android.graphics.Color.green(after.getPixel(x, y))); totalDiff += kotlin.math.abs(android.graphics.Color.blue(before.getPixel(x, y)) - android.graphics.Color.blue(after.getPixel(x, y))); sampleCount++; x += sampleStepX }; y += sampleStepY }
        return if (sampleCount == 0) 0f else totalDiff.toFloat() / (sampleCount * 255f * 3f)
    }

    private fun isSolvedResidualDetection(before: SliderTFLite.SlideRecognitionResult?, after: SliderTFLite.SlideRecognitionResult, cb: Bitmap): Boolean {
        if (before == null || after.candidateCount != 1) return false
        val tdx = kotlin.math.abs(after.sliderX - before.targetX); val tdy = kotlin.math.abs(after.sliderY - before.targetY)
        val collapsed = kotlin.math.abs(after.targetX - after.sliderX) <= 1f && kotlin.math.abs(after.targetY - after.sliderY) <= 1f
        return collapsed && tdx <= maxOf(cb.width * 0.12f, 90f) && tdy <= maxOf(cb.height * 0.10f, 80f)
    }

    private fun estimateCorrectionDistance(recognitionResult: SliderTFLite.SlideRecognitionResult): Float {
        val raw = recognitionResult.targetX - recognitionResult.sliderX
        return if (kotlin.math.abs(raw) < 1f) 12f else if (raw > 0f) raw + 8f else raw - 8f
    }

    private fun isLikelySliderHandleBlue(pixel: Int): Boolean { val r = android.graphics.Color.red(pixel); val g = android.graphics.Color.green(pixel); val b = android.graphics.Color.blue(pixel); return b >= 140 && g >= 60 && r <= 160 && b - r >= 40 && b - g >= 15 }
    private fun formatAnchorReason(anchorText: String?) = if (anchorText.isNullOrBlank()) "text-anchor-missing" else "text-anchor=$anchorText"
    private fun formatRecognition(result: SliderTFLite.SlideRecognitionResult?) = if (result == null) "none" else "slider=(${result.sliderX.toInt()},${result.sliderY.toInt()}), target=(${result.targetX.toInt()},${result.targetY.toInt()}), confidence=${result.confidence}, candidates=${result.candidateCount}"
    private fun isMainThread() = Looper.myLooper() == Looper.getMainLooper()
    private fun saveDebugBitmap(bitmap: Bitmap, fileName: String) { try { val ctx = SimplePageManager.getContext() ?: return; val dir = java.io.File(ctx.cacheDir, "captcha_debug"); if (!dir.exists()) dir.mkdirs(); val file = java.io.File(dir, "${System.currentTimeMillis()}_$fileName.jpg"); java.io.FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; Log.record("CaptchaDebug", "调试图片已导出: ${file.absolutePath}") } catch(e: Exception) { Log.error("CaptchaDebug", "导出失败: ${e.message}") } }
    private fun getBitmapFromView(view: View): Bitmap? { if (view.width <= 0 || view.height <= 0) return null; val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(b)); return b }

    // Stub legacy methods
    private suspend fun handleLegacySlideCaptcha(activity: Activity): ActivityHandleResult = ActivityHandleResult.SKIP_NON_RETRYABLE
    private fun findSliderByFeature(root: View): View? = null
    private fun findSliderByBottomSliderArea(root: View): View? = null
    private fun findSliderByTextAnchor(): View? = null
    private fun findCaptchaImageView(sliderView: View): ImageView? = null
    private suspend fun recognizeCaptchaGapNative(view: View): Pair<Int, Float>? = null
    private fun calculateDistance(gapXInImage: Int, imageRealWidth: Int, bgView: View, sliderView: View): Float = 0f
    private fun calculateLegacySlideCoordinates(activity: Activity, sliderView: View): SlideCoordinates? = null
    private suspend fun performSlide(activity: Activity, sliderView: View, distance: Float): Boolean = false
    private suspend fun executeSlide(sliderView: View, startX: Float, startY: Float, endX: Float, endY: Float): Boolean = false
    private fun checkCaptchaTextGoneLegacyOnly(): Boolean = true
}
