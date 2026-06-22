package fansirsqi.xposed.sesame.hook.simple

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.delay
import kotlin.random.Random

object MotionEventSimulator {

    private const val TAG = "MotionEventSimulator"
    private data class LocalPoint(val x: Float, val y: Float)

    suspend fun simulateSwipe(view: View, startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 800L) {
        if (!view.isShown || !view.isEnabled) return
        val localStart = toLocalPoint(view, startX, startY)
        val localEnd = toLocalPoint(view, endX, endY)
        val downTime = SystemClock.uptimeMillis()
        try {
            dispatchTouchEvent(view, MotionEvent.ACTION_DOWN, localStart.x, localStart.y, downTime, downTime)
            delay(Random.nextLong(80, 140))
            val distance = localEnd.x - localStart.x
            val tracks = generateHumanLikeTracks(distance)
            val stepDelay = if (tracks.isEmpty()) 12L else (duration / tracks.size).coerceIn(8L, 28L)
            var lastEventTime = downTime
            tracks.forEach { (relX, relY) ->
                val currentX = localStart.x + relX
                val currentY = localStart.y + relY + Random.nextInt(-2, 3)
                lastEventTime += stepDelay + Random.nextLong(0, 8)
                dispatchTouchEvent(view, MotionEvent.ACTION_MOVE, currentX, currentY, downTime, lastEventTime)
                delay(stepDelay)
            }
            val finalUpTime = lastEventTime + Random.nextLong(60, 120)
            delay(Random.nextLong(50, 90))
            dispatchTouchEvent(view, MotionEvent.ACTION_UP, localEnd.x, localEnd.y, downTime, finalUpTime)
        } catch (e: Throwable) { Log.e(TAG, "滑动异常", e) }
    }

    private fun generateHumanLikeTracks(totalDistance: Float): List<Pair<Float, Float>> {
        val tracks = mutableListOf<Pair<Float, Float>>()
        var currentX = 0f
        // P2-01.5: 随机化物理参数(±30%)，模拟人类操作的不确定性
        var v = Random.nextFloat() * 1.5f
        val a_accel = 1.5f * (0.7f + Random.nextFloat() * 0.6f)
        val a_decel = -2.0f * (0.7f + Random.nextFloat() * 0.6f)
        val mid = totalDistance * (0.60f + Random.nextFloat() * 0.15f)

        // 40%概率在25%-55%处插入微停顿
        val microPauseAt = if (Random.nextFloat() < 0.4f) totalDistance * (0.25f + Random.nextFloat() * 0.3f) else -1f
        var paused = false

        while (currentX < totalDistance) {
            val a = if (currentX < mid) a_accel else a_decel
            val t = (0.3f + Random.nextFloat() * 0.4f) * (0.8f + Random.nextFloat() * 0.4f)
            val move = v * t + 0.5f * a * t * t; v += a * t
            if (v < 0.3f && currentX >= mid) v = 0.3f + Random.nextFloat() * 0.5f

            // 微停顿逻辑
            if (!paused && microPauseAt > 0 && currentX >= microPauseAt) {
                paused = true
                repeat(Random.nextInt(1, 4)) {
                    currentX += Random.nextFloat() * 1.5f
                    tracks.add(Pair(currentX, Random.nextInt(-2, 3).toFloat()))
                }
            }

            currentX += move
            val yJitter = (if (v > 3f) Random.nextInt(-3, 4) else Random.nextInt(-1, 2)).toFloat()
            tracks.add(Pair(currentX, yJitter))
            if (currentX > totalDistance) break
        }

        // 85%概率触发回摆
        if (Random.nextFloat() < 0.85f) {
            val overshoot = totalDistance * (0.01f + Random.nextFloat() * 0.03f) + Random.nextFloat() * 3f
            tracks.add(Pair(totalDistance + overshoot, Random.nextInt(-1, 2).toFloat()))
            tracks.add(Pair(totalDistance + (overshoot / 2), Random.nextInt(-1, 2).toFloat()))
        }
        tracks.add(Pair(totalDistance, 0f))
        return tracks
    }

    private fun dispatchTouchEvent(view: View, action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val cords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val event = MotionEvent.obtain(downTime, eventTime, action, 1, props, cords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        view.dispatchTouchEvent(event); event.recycle()
    }

    private fun toLocalPoint(view: View, screenX: Float, screenY: Float): LocalPoint {
        val location = IntArray(2); view.getLocationOnScreen(location)
        return LocalPoint(screenX - location[0], screenY - location[1])
    }
}
