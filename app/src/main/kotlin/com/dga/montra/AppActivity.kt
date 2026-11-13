package com.dga.montra

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.dga.montra.data.Settings

/**
 * The parts every screen needs and none of them should restate: night mode applied before the first
 * frame, edge-to-edge insets applied once, and one toast helper.
 *
 * Insets are handled here because `targetSdk 35` enforces edge-to-edge: the status and navigation
 * bars overlay the content, and `fitsSystemWindows` is the wrong tool for a bottom bar that should
 * paint behind the gesture area while its content stays above it.
 */
abstract class AppActivity : Activity() {
    internal val settings: Settings by lazy { Settings(this) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        applyNightMode(settings.themeMode)
    }

    override fun setContentView(view: View) {
        super.setContentView(view)
        applyEdgeToEdge(view)
    }

    override fun setContentView(layoutResId: Int) {
        super.setContentView(layoutResId)
        findViewById<View>(android.R.id.content)?.let(::applyEdgeToEdge)
    }

    protected fun toast(message: Int, short: Boolean = false) {
        Toast.makeText(this, message, if (short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
    }

    /**
     * Applies the saved scheme to this Activity's resources. `AppCompatDelegate` is the end state;
     * it needs `AppCompatActivity`, which is the next step for the dialog migration, so the
     * deprecated-but-contained call lives here instead of in every screen.
     */
    private fun applyNightMode(mode: Int) {
        val current = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val target = when (mode) {
            Settings.THEME_LIGHT -> Configuration.UI_MODE_NIGHT_NO
            Settings.THEME_DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> current
        }
        if (target == current) return
        val configuration = Configuration(resources.configuration)
        configuration.uiMode = target
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    /**
     * Status-bar and cutout insets become padding on the screen; the bottom inset becomes padding on
     * the navigation bar when one exists, so its surface still reaches the screen edge.
     */
    private fun applyEdgeToEdge(content: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, content).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

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
            val ownsBottomInset = bottomBar == null
            view.updatePadding(
                left = baseLeft + bars.left,
                top = baseTop + bars.top,
                right = baseRight + bars.right,
                bottom = baseBottom + if (ownsBottomInset) bars.bottom else 0
            )
            bottomBar?.updatePadding(bottom = barBaseBottom + bars.bottom)
            insets
        }
    }
}
