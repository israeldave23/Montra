package com.dga.montra

import android.app.Activity
import android.os.Bundle

abstract class SectionActivity(
    val tab: Int
) : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
    }
}
