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
        var currentX = 0f; var v = 0f
        val a_accel = 1.5f; val a_decel = -2.0f
        val mid = totalDistance * 0.7f
        while (currentX < totalDistance) {
            val a = if (currentX < mid) a_accel else a_decel
            val t = 0.5f; val move = v * t + 0.5f * a * t * t; v += a * t
            if (v < 0.5f && currentX >= mid) v = 0.5f
            currentX += move; tracks.add(Pair(currentX, 0f))
            if (currentX > totalDistance) break
        }
        val overshoot = Random.nextFloat() * 5f + 2f
        tracks.add(Pair(totalDistance + overshoot, 0f))
        tracks.add(Pair(totalDistance + (overshoot / 2), 0f))
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
