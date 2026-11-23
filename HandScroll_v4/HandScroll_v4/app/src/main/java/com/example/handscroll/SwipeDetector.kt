package com.example.handscroll

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turns hand landmarks into actions:
 *  - open palm flick up / down  -> NEXT / PREVIOUS
 *  - fist held ~0.8 s           -> PLAY_PAUSE
 *  - thumbs-up held ~0.8 s      -> LIKE
 * After a swipe the hand moving back is ignored until the hand rests or leaves the frame.
 */
class SwipeDetector(private val prefs: Prefs) {

    enum class Action { NEXT, PREVIOUS, PLAY_PAUSE, LIKE }

    private enum class Pose { NONE, FIST, THUMB }

    private class Sample(val t: Long, val x: Float, val y: Float)

    private val samples = ArrayDeque<Sample>()
    private var cooldownUntil = 0L
    private var armed = true
    private var pose = Pose.NONE
    private var poseSince = 0L
    private var poseFired = false

    fun reset() {
        samples.clear()
        armed = true
        pose = Pose.NONE
        poseFired = false
    }

    fun onNoHand() = reset()

    /** @param aspect width / height of the (upright) analysed frame. */
    fun onHand(l: List<NormalizedLandmark>, aspect: Float, now: Long): Action? {
        // ---- held poses (fist / thumbs-up) ----
        val curled = curledCount(l, aspect)
        val p = when {
            curled == 4 && isThumbUp(l, aspect) -> Pose.THUMB
            curled == 4 -> Pose.FIST
            else -> Pose.NONE
        }
        if (p != Pose.NONE) {
            samples.clear()
            if (p != pose) {
                pose = p
                poseSince = now
                poseFired = false
            }
            val enabled = if (p == Pose.THUMB) prefs.thumbLike else prefs.fistPlayPause
            if (enabled && !poseFired && now >= cooldownUntil && now - poseSince >= HOLD_MS) {
                poseFired = true
                cooldownUntil = now + prefs.cooldownMs
                return if (p == Pose.THUMB) Action.LIKE else Action.PLAY_PAUSE
            }
            return null
        }
        pose = Pose.NONE
        poseFired = false

        // ---- swipe ----
        if (prefs.requireOpenPalm && !isOpenPalm(l, aspect)) {
            samples.clear()
            return null
        }

        var cx = 0f
        var cy = 0f
        for (i in PALM) { cx += l[i].x(); cy += l[i].y() }
        cx /= PALM.size
        cy /= PALM.size

        samples.addLast(Sample(now, cx, cy))
        while (samples.isNotEmpty() && now - samples.first().t > WINDOW_MS) samples.removeFirst()
        if (samples.size < 4) return null

        if (!armed) {
            val range = samples.maxOf { it.y } - samples.minOf { it.y }
            if (now >= cooldownUntil && range < REARM_RANGE) armed = true
            return null
        }
        if (now < cooldownUntil) return null

        val first = samples.first()
        val dy = cy - first.y
        val dx = (cx - first.x) * aspect
        if (abs(dy) < prefs.threshold) return null
        if (abs(dx) > abs(dy) * 0.8f) return null

        armed = false
        cooldownUntil = now + prefs.cooldownMs
        samples.clear()
        return if (dy < 0) Action.NEXT else Action.PREVIOUS   // y grows downward
    }

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark, aspect: Float): Float =
        hypot((a.x() - b.x()) * aspect, a.y() - b.y())

    private fun isOpenPalm(l: List<NormalizedLandmark>, aspect: Float): Boolean {
        var extended = 0
        for ((tip, pip) in FINGERS) {
            if (dist(l[tip], l[0], aspect) > dist(l[pip], l[0], aspect) * 1.1f) extended++
        }
        return extended >= 3
    }

    private fun curledCount(l: List<NormalizedLandmark>, aspect: Float): Int {
        var n = 0
        for ((tip, pip, mcp) in FINGERS3) {
            val dt = dist(l[tip], l[0], aspect)
            if (dt < dist(l[pip], l[0], aspect) * 0.95f && dt < dist(l[mcp], l[0], aspect) * 1.2f) n++
        }
        return n
    }

    /** Thumb tip clearly above its base and above the index knuckle, pointing mostly up. */
    private fun isThumbUp(l: List<NormalizedLandmark>, aspect: Float): Boolean {
        val tip = l[4]
        val mcp = l[2]
        val up = mcp.y() - tip.y()
        return up > 0.06f &&
                tip.y() < l[5].y() - 0.03f &&
                up > abs(tip.x() - mcp.x()) * aspect
    }

    private companion object {
        const val WINDOW_MS = 450L
        const val REARM_RANGE = 0.05f
        const val HOLD_MS = 800L
        val PALM = intArrayOf(0, 5, 9, 13, 17)
        val FINGERS = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18)
        val FINGERS3 = listOf(
            Triple(8, 6, 5), Triple(12, 10, 9), Triple(16, 14, 13), Triple(20, 18, 17)
        )
    }
}
