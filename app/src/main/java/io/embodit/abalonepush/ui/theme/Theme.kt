package io.embodit.abalonepush.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import io.embodit.abalonepush.domain.Language
import io.embodit.abalonepush.platform.Graph
import java.util.Locale

/** design-package theme.colorScheme: an abalone shell on a seaside table (deep-sea teal, walnut, pearl / ink glass, amber). */
object AbColors {
    val Primary = Color(0xFF1F4E5F)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFBFE3EC)
    val OnPrimaryContainer = Color(0xFF062A35)
    val Secondary = Color(0xFF8A5A2B)
    val SecondaryContainer = Color(0xFFF0D9BC)
    val OnSecondaryContainer = Color(0xFF3E2408)
    val Tertiary = Color(0xFFD99A2B)
    val OnTertiary = Color(0xFF2B1D05)
    val TertiaryContainer = Color(0xFFFFE2A8)
    val OnTertiaryContainer = Color(0xFF3E2A00)
    val Error = Color(0xFFC8423F)
    val OnError = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1B1F23)
    /** Secondary / small text on the window glass (>= 4.5:1 on the darkest measured glass #B4B3B1). */
    val InkSecondary = Color(0xFF3A3F44)
    /** Error text on glass / light cards; the brighter Error stays for 3D marks and button fills. */
    val ErrorText = Color(0xFF7A1A18)
    val Amber = Color(0xFFD99A2B)
    val Blue = Color(0xFF4C8DD2)

    // 3D / plate
    val MarbleBlack = Color(0xFF141518)
    val MarbleWhite = Color(0xFFF2EEE6)
    val Wood = Color(0xFFE2C08D)
    val Walnut = Color(0xFF6B4A2B)
    val TagFace = Color(0xFFFBF6EC)
}

/**
 * PicoTheme with all 16 roles assigned (Cloud Air pattern). The language setting localises resources below this
 * point through a ContextWrapper that only overrides getResources (a bare configuration context breaks SpatialView).
 */
@Composable
fun AbaloneTheme(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val graph = remember(base) { Graph.from(base) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val localized = remember(base, settings.language) { base.localized(settings.language) }
    val system = systemColorScheme(localized)
    val scheme = system.copy(
        fillPrimary = system.fillPrimary,
        fillSecondary = system.fillSecondary,
        fillTertiary = system.fillTertiary,
        fillLight = system.fillLight,
        labelPrimaryLight = AbColors.OnPrimary,
        labelPrimary = AbColors.Ink,
        labelSecondary = AbColors.InkSecondary,
        labelTertiary = system.labelTertiary,
        labelQuaternary = system.labelQuaternary,
        lightenHover = system.lightenHover,
        lightenPressed = system.lightenPressed,
        error = AbColors.Error,
        alert = AbColors.Amber,
        passable = AbColors.Secondary,
        interaction = AbColors.Primary,
        dividerLine = system.dividerLine,
    )
    CompositionLocalProvider(LocalContext provides localized) {
        PicoTheme(colorScheme = scheme, typography = PicoTheme.typography.withNotoSans(), content = content)
    }
}

fun Context.localized(language: Language): Context {
    val locale = when (language) {
        Language.SYSTEM -> return this
        Language.ZH -> Locale.SIMPLIFIED_CHINESE
        Language.EN -> Locale.ENGLISH
    }
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    val res = createConfigurationContext(config).resources
    return object : ContextWrapper(this) {
        override fun getResources(): Resources = res
    }
}
