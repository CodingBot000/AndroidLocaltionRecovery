package lab.recovery.simulation

import kotlinx.coroutines.delay
import kotlin.math.ceil

interface ReplayClock {
    fun nowMs(): Long
    suspend fun await(delayMs: Long)
}

object MonotonicReplayClock : ReplayClock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000L
    override suspend fun await(delayMs: Long) = delay(delayMs)
}

internal class ReplayTimeline(private val clock: ReplayClock) {
    private var virtualAnchor = 0.0
    private var realAnchor = clock.nowMs()
    private var rate = 0f

    fun position(): Double = virtualAnchor + (clock.nowMs() - realAnchor) * rate.toDouble()

    fun changeRate(speed: Float) {
        val now = clock.nowMs()
        virtualAnchor += (now - realAnchor) * rate.toDouble()
        realAnchor = now
        rate = speed
    }

    fun seek(timeMs: Long) {
        virtualAnchor = timeMs.toDouble()
        realAnchor = clock.nowMs()
        rate = 0f
    }

    fun delayUntil(timeMs: Long): Long =
        ceil((timeMs - position()) / rate).toLong().coerceAtLeast(0)
}
