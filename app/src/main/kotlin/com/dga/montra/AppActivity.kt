package com.dga.montra

import android.content.res.Configuration
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.dga.montra.data.Settings

/**
 * The parts every screen needs and none of them should restate: the theme (applied once, by
 * `AppCompatDelegate`), edge-to-edge insets applied once, and one toast helper.
 *
 * Insets are handled here because `targetSdk 36` enforces edge-to-edge with no opt-out left: on
 * Android 16 the platform stops fitting the window to the system bars, whatever the app asks for.
 * `fitsSystemWindows` is the wrong tool for a bottom bar that should paint behind the gesture area
 * while its content stays above it, so every screen gets one inset listener from this base class.
 *
 * Target 36 also stops calling `onBackPressed` and stops dispatching `KEYCODE_BACK` on Android 16.
 * Nothing in this app intercepts back - screens rely on the default finish, and dialogs cancel through
 * the framework - so predictive back needs no work here. That stays true only while it stays true.
 *
 * This used to be an `Activity`, and applying the scheme meant calling
 * `resources.updateConfiguration` — deprecated, and wrong for anything created from the Activity
 * context (dialogs kept the old scheme). `AppCompatDelegate` fixes both: it owns the mode for the
 * whole process and recreates what needs recreating.
 */
abstract class AppActivity : AppCompatActivity() {
    internal val settings: Settings by lazy { Settings(this) }

    /** AppCompat routes `setContentView(int)` through `setContentView(View)`, so guard the second pass. */
    private var insetsApplied = false

    override fun setContentView(view: View?) {
        super.setContentView(view)
        view?.let(::applyEdgeToEdge)
    }

    override fun setContentView(layoutResId: Int) {
        super.setContentView(layoutResId)
        findViewById<View>(android.R.id.content)?.let(::applyEdgeToEdge)
    }

    protected fun toast(message: Int, short: Boolean = false) {
        Toast.makeText(this, message, if (short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
    }

    private fun applyBarIcons(content: View) {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, content).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    /**
     * Status-bar and cutout insets become padding on the screen; the bottom inset becomes padding on
     * the navigation bar when one exists, so its surface still reaches the screen edge.
     */
    private fun applyEdgeToEdge(content: View) {
        if (insetsApplied) return
        insetsApplied = true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyBarIcons(content)

        val baseLeft = content.paddingLeft
        val baseTop = content.paddingTop
        val baseRight = content.paddingRight
        val baseBottom = content.paddingBottom
        val bottomBar = content.findViewById<View>(R.id.bottom_navigation)
        val barBaseBottom = bottomBar?.paddingBottom ?: 0

        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val ownsBottomInset = bottomBar == null
            // An open keyboard sits on top of the navigation bar, so it takes over the bottom inset
            // entirely - the whole screen lifts above it, bottom bar included. Without edge-to-edge the
            // window would have been resized for us; with it, this is the app's job.
            val bottom = if (keyboard > 0) keyboard else if (ownsBottomInset) bars.bottom else 0
            view.updatePadding(
                left = baseLeft + bars.left,
                top = baseTop + bars.top,
                right = baseRight + bars.right,
                bottom = baseBottom + bottom
            )
            // The bar's own surface still reaches the screen edge when no keyboard is in the way.
            bottomBar?.updatePadding(bottom = barBaseBottom + if (keyboard > 0) 0 else bars.bottom)
            insets
        }
    }

    companion object {
        /**
         * Applies [mode] for the whole process. AppCompat recreates the visible screen when the
         * scheme actually changes, so callers must not call `recreate()` themselves.
         */
        fun applyThemeMode(mode: Int) {
            val night = when (mode) {
                Settings.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                Settings.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (AppCompatDelegate.getDefaultNightMode() != night) {
                AppCompatDelegate.setDefaultNightMode(night)
            }
        }
    }
}
