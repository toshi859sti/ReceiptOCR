package com.example.greenframeocr.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.greenframeocr.data.AppDarkMode
import com.example.greenframeocr.data.AppThemePreset

private val GreenLight = lightColorScheme(
    primary            = Green40,
    onPrimary          = GreenOnPrimary,
    primaryContainer   = GreenContainer,
    onPrimaryContainer = GreenOnContainer,
    secondary          = GreenGrey40,
    tertiary           = GreenAccent40,
)
private val GreenDark = darkColorScheme(
    primary            = Green80,
    onPrimary          = GreenOnPrimaryDark,
    primaryContainer   = GreenContainerDark,
    onPrimaryContainer = GreenOnContainerDark,
    secondary          = GreenGrey80,
    tertiary           = GreenAccent80,
)

private val PurpleLight = lightColorScheme(
    primary            = Purple40,
    onPrimary          = PurpleOnPrimary,
    primaryContainer   = PurpleContainer,
    onPrimaryContainer = PurpleOnContainer,
    secondary          = PurpleGrey40,
    tertiary           = Pink40,
)
private val PurpleDark = darkColorScheme(
    primary            = Purple80,
    onPrimary          = PurpleOnPrimaryDark,
    primaryContainer   = PurpleContainerDark,
    onPrimaryContainer = PurpleOnContainerDark,
    secondary          = PurpleGrey80,
    tertiary           = Pink80,
)

private val BlueLight = lightColorScheme(
    primary            = Blue40,
    onPrimary          = BlueOnPrimary,
    primaryContainer   = BlueContainer,
    onPrimaryContainer = BlueOnContainer,
    secondary          = BlueGrey40,
    tertiary           = Cyan40,
)
private val BlueDark = darkColorScheme(
    primary            = Blue80,
    onPrimary          = BlueOnPrimaryDark,
    primaryContainer   = BlueContainerDark,
    onPrimaryContainer = BlueOnContainerDark,
    secondary          = BlueGrey80,
    tertiary           = Cyan80,
)

private val TerraLight = lightColorScheme(
    primary            = Terra40,
    onPrimary          = TerraOnPrimary,
    primaryContainer   = TerraContainer,
    onPrimaryContainer = TerraOnContainer,
    secondary          = TerraGrey40,
    tertiary           = Amber40,
)
private val TerraDark = darkColorScheme(
    primary            = Terra80,
    onPrimary          = TerraOnPrimaryDark,
    primaryContainer   = TerraContainerDark,
    onPrimaryContainer = TerraOnContainerDark,
    secondary          = TerraGrey80,
    tertiary           = Amber80,
)

private val MonoLight = lightColorScheme(
    primary            = Grey40,
    onPrimary          = GreyOnPrimary,
    primaryContainer   = GreyContainer,
    onPrimaryContainer = GreyOnContainer,
    secondary          = GreyMid40,
    tertiary           = GreyAcc40,
)
private val MonoDark = darkColorScheme(
    primary            = Grey80,
    onPrimary          = GreyOnPrimaryDark,
    primaryContainer   = GreyContainerDark,
    onPrimaryContainer = GreyOnContainerDark,
    secondary          = GreyMid80,
    tertiary           = GreyAcc80,
)

private fun buildColorScheme(preset: AppThemePreset, dark: Boolean): ColorScheme = when (preset) {
    AppThemePreset.GREEN  -> if (dark) GreenDark  else GreenLight
    AppThemePreset.PURPLE -> if (dark) PurpleDark else PurpleLight
    AppThemePreset.BLUE   -> if (dark) BlueDark   else BlueLight
    AppThemePreset.TERRA  -> if (dark) TerraDark  else TerraLight
    AppThemePreset.MONO   -> if (dark) MonoDark   else MonoLight
}

@Composable
fun ReceiptOCRTheme(
    themePreset: AppThemePreset = AppThemePreset.GREEN,
    darkMode: AppDarkMode = AppDarkMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (darkMode) {
        AppDarkMode.SYSTEM -> systemDark
        AppDarkMode.LIGHT  -> false
        AppDarkMode.DARK   -> true
    }
    val colorScheme = buildColorScheme(themePreset, darkTheme)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            // ライトモード: primary は濃色 → 白アイコン(false)
            // ダークモード: primary は淡色 → 黒アイコン(true)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
