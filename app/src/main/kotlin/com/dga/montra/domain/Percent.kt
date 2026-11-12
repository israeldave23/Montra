package com.dga.montra.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Floors [part] / [whole] as a whole percentage clamped to 0..[cap].
 * A non-positive [whole] yields 0, and the value is clamped before conversion so extreme
 * ratios can never overflow.
 */
internal fun percentOf(part: Long, whole: Long, cap: Int = 100): Int {
    if (whole <= 0L || cap <= 0) return 0
    val value = BigDecimal.valueOf(part.coerceAtLeast(0L))
        .multiply(BigDecimal.valueOf(100L))
        .divide(BigDecimal.valueOf(whole), 0, RoundingMode.DOWN)
    return value
        .max(BigDecimal.ZERO)
        .min(BigDecimal.valueOf(cap.toLong()))
        .toInt()
}

/** Signed percentage change from [previous] to [current]; null when there is no baseline. */
internal fun percentChange(current: Long, previous: Long): Int? {
    if (previous <= 0L) return null
    val delta = BigDecimal.valueOf(current)
        .subtract(BigDecimal.valueOf(previous))
        .multiply(BigDecimal.valueOf(100L))
        .divide(BigDecimal.valueOf(previous), 0, RoundingMode.HALF_UP)
    return delta.max(BigDecimal.valueOf(-9_999L)).min(BigDecimal.valueOf(9_999L)).toInt()
}
