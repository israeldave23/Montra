package com.dga.montra

/**
 * A screen reachable from the bottom navigation. It exists only to carry the selected tab; the
 * window, theme and inset wiring lives in [AppActivity].
 */
abstract class SectionActivity(
    val tab: Int
) : AppActivity()
