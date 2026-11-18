package com.mingxin.feynman.data

import kotlin.math.roundToInt

/**
 * 间隔调度（FSRS 简化）。
 *
 * 复习对象是「理解卡」：你自己的卡点。
 * 打分含义：1=还是卡 2=说清了一些 3=讲通了 4=很熟练
 * "还是卡"不该把间隔清零——卡点反复是常态，重置反而让用户永远在打转。
 */
object Scheduler {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MIN_EASE = 1.3f
    private const val MAX_EASE = 3.0f

    data class Result(val intervalDays: Int, val ease: Float, val streak: Int, val dueAt: Long)

    fun schedule(
        now: Long,
        intervalDays: Int,
        ease: Float,
        streak: Int,
        grade: Int
    ): Result {
        val prev = if (intervalDays <= 0) 1 else intervalDays
        val ease0 = if (ease <= 0f) 2.5f else ease

        val (interval, newEase, newStreak) = when (grade) {
            // 还是卡：明天再来，但别重置到零（避免原地打转的挫折感）
            1 -> Triple(1, (ease0 - 0.2f).coerceAtLeast(MIN_EASE), 0)
            2 -> Triple((prev * 1.2f).roundToInt().coerceAtLeast(2), (ease0 - 0.1f).coerceAtLeast(MIN_EASE), streak + 1)
            3 -> Triple((prev * ease0).roundToInt().coerceAtLeast(prev + 1), ease0, streak + 1)
            else -> Triple(
                (prev * ease0 * 1.3f).roundToInt().coerceAtLeast(prev + 1),
                (ease0 + 0.15f).coerceAtMost(MAX_EASE),
                streak + 1
            )
        }
        return Result(interval, newEase, newStreak, now + interval * DAY_MS)
    }

    fun dayStart(ts: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = ts
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}
