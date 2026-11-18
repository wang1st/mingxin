package com.mingxin.feynman.data

import kotlin.math.roundToInt

/**
 * 简化的 FSRS / SM-2 混合调度：只要够用、可预测，不要学术复杂度。
 *
 * grade: 1=忘记 2=吃力 3=想起 4=轻松
 * - 忘记：重置到 1 天，ease 降 0.2（不低于 1.3）
 * - 吃力：间隔 ×1.2，ease 降 0.15
 * - 想起：间隔 × ease
 * - 轻松：间隔 × ease × 1.3，ease 升 0.15（不高于 3.0）
 */
object Scheduler {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MIN_EASE = 1.3f
    private const val MAX_EASE = 3.0f

    data class Result(val intervalDays: Int, val ease: Float, val streak: Int, val dueAt: Long)

    fun schedule(d: DoubtEntity, grade: Int, now: Long = System.currentTimeMillis()): Result {
        val prev = if (d.intervalDays <= 0) 1 else d.intervalDays
        val ease0 = if (d.ease <= 0f) 2.5f else d.ease

        val (interval, ease, streak) = when (grade) {
            1 -> Triple(1, (ease0 - 0.2f).coerceAtLeast(MIN_EASE), 0)
            2 -> Triple((prev * 1.2f).roundToInt().coerceAtLeast(2), (ease0 - 0.15f).coerceAtLeast(MIN_EASE), d.streak + 1)
            3 -> Triple((prev * ease0).roundToInt().coerceAtLeast(prev + 1), ease0, d.streak + 1)
            else -> Triple((prev * ease0 * 1.3f).roundToInt().coerceAtLeast(prev + 1), (ease0 + 0.15f).coerceAtMost(MAX_EASE), d.streak + 1)
        }
        return Result(
            intervalDays = interval,
            ease = ease,
            streak = streak,
            dueAt = now + interval * DAY_MS
        )
    }

    fun dayStart(ts: Long): Long {
        val c = java.util.Calendar.getInstance().apply {
            timeInMillis = ts
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }
}
