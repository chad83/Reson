package com.reson.player.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.reson.player.data.Skin

private val greenDark = darkColorScheme(
    primary = Color(0xFFA4F4CD),
    onPrimary = Color(0xFF10271F),
    primaryContainer = Color(0xFF1E4137),
    onPrimaryContainer = Color(0xFFB8F7D9),
    background = Color(0xFF111719),
    onBackground = Color(0xFFEDF3F0),
    surface = Color(0xFF111719),
    onSurface = Color(0xFFEDF3F0),
    surfaceVariant = Color(0xFF202A2C),
    onSurfaceVariant = Color(0xFFBAC8C4),
    surfaceContainer = Color(0xFF1A2325),
    surfaceContainerHigh = Color(0xFF243032),
    outlineVariant = Color(0xFF344345),
)

private val greenLight = lightColorScheme(
    primary = Color(0xFF246448),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEDDC),
    onPrimaryContainer = Color(0xFF143D2B),
    background = Color(0xFFF5F7F2),
    onBackground = Color(0xFF182522),
    surface = Color(0xFFF5F7F2),
    onSurface = Color(0xFF182522),
    surfaceVariant = Color(0xFFE1EAE4),
    onSurfaceVariant = Color(0xFF475953),
    surfaceContainer = Color(0xFFEAF0EA),
    surfaceContainerHigh = Color(0xFFDDE8E0),
    outlineVariant = Color(0xFFCBD8D0),
)

private val purpleLight = lightColorScheme(
    primary = Color(0xFF69429A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEEDCFF),
    onPrimaryContainer = Color(0xFF321052),
    secondary = Color(0xFF655571),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDDEF8),
    onSecondaryContainer = Color(0xFF382748),
    tertiary = Color(0xFF82516C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFAD9EA),
    onTertiaryContainer = Color(0xFF482339),
    background = Color(0xFFFCF7FF),
    onBackground = Color(0xFF251D2D),
    surface = Color(0xFFFCF7FF),
    onSurface = Color(0xFF251D2D),
    surfaceVariant = Color(0xFFEDE3F3),
    onSurfaceVariant = Color(0xFF534859),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F0FB),
    surfaceContainer = Color(0xFFF2E8F7),
    surfaceContainerHigh = Color(0xFFEDE1F3),
    surfaceContainerHighest = Color(0xFFE6D8ED),
    outline = Color(0xFF77677E),
    outlineVariant = Color(0xFFD8C9E1),
    inverseSurface = Color(0xFF342B3D),
    inverseOnSurface = Color(0xFFF2E8F7),
    inversePrimary = Color(0xFFDDBAFF),
)

private val purpleDark = darkColorScheme(
    primary = Color(0xFFDDBAFF),
    onPrimary = Color(0xFF371460),
    primaryContainer = Color(0xFF512879),
    onPrimaryContainer = Color(0xFFF0DDFF),
    secondary = Color(0xFFD0BEDF),
    onSecondary = Color(0xFF362942),
    secondaryContainer = Color(0xFF4C3F57),
    onSecondaryContainer = Color(0xFFEDDEF8),
    tertiary = Color(0xFFF4B4D6),
    onTertiary = Color(0xFF4B223A),
    tertiaryContainer = Color(0xFF633950),
    onTertiaryContainer = Color(0xFFFAD9EA),
    background = Color(0xFF19151E),
    onBackground = Color(0xFFF2E8F7),
    surface = Color(0xFF19151E),
    onSurface = Color(0xFFF2E8F7),
    surfaceVariant = Color(0xFF342B3D),
    onSurfaceVariant = Color(0xFFD2C3DB),
    surfaceContainerLowest = Color(0xFF140F19),
    surfaceContainerLow = Color(0xFF201A27),
    surfaceContainer = Color(0xFF282030),
    surfaceContainerHigh = Color(0xFF32293B),
    surfaceContainerHighest = Color(0xFF3D3247),
    outline = Color(0xFFA293AC),
    outlineVariant = Color(0xFF4F4259),
    inverseSurface = Color(0xFFF2E8F7),
    inverseOnSurface = Color(0xFF251D2D),
    inversePrimary = Color(0xFF69429A),
)

private val orangeLight = lightColorScheme(
    primary = Color(0xFF964906),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCBC),
    onPrimaryContainer = Color(0xFF512400),
    secondary = Color(0xFF795741),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBC5),
    onSecondaryContainer = Color(0xFF442B1B),
    tertiary = Color(0xFF745B22),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE1A3),
    onTertiaryContainer = Color(0xFF402E05),
    background = Color(0xFFFFF8F2),
    onBackground = Color(0xFF302217),
    surface = Color(0xFFFFF8F2),
    onSurface = Color(0xFF302217),
    surfaceVariant = Color(0xFFF4E4D8),
    onSurfaceVariant = Color(0xFF604B3D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1E6),
    surfaceContainer = Color(0xFFFAE9DC),
    surfaceContainerHigh = Color(0xFFF3E0D2),
    surfaceContainerHighest = Color(0xFFECD7C8),
    outline = Color(0xFF8A7261),
    outlineVariant = Color(0xFFDCC9BA),
    inverseSurface = Color(0xFF3E3025),
    inverseOnSurface = Color(0xFFF8EADD),
    inversePrimary = Color(0xFFFFB77C),
)

private val orangeDark = darkColorScheme(
    primary = Color(0xFFFFB77C),
    onPrimary = Color(0xFF512400),
    primaryContainer = Color(0xFF733500),
    onPrimaryContainer = Color(0xFFFFDCBC),
    secondary = Color(0xFFEABEA2),
    onSecondary = Color(0xFF442B1B),
    secondaryContainer = Color(0xFF5F412C),
    onSecondaryContainer = Color(0xFFFFDBC5),
    tertiary = Color(0xFFE9C67C),
    onTertiary = Color(0xFF402E05),
    tertiaryContainer = Color(0xFF594414),
    onTertiaryContainer = Color(0xFFFFE1A3),
    background = Color(0xFF1E160F),
    onBackground = Color(0xFFF8EADD),
    surface = Color(0xFF1E160F),
    onSurface = Color(0xFFF8EADD),
    surfaceVariant = Color(0xFF3E3025),
    onSurfaceVariant = Color(0xFFDEC5B4),
    surfaceContainerLowest = Color(0xFF190F09),
    surfaceContainerLow = Color(0xFF271D15),
    surfaceContainer = Color(0xFF30231A),
    surfaceContainerHigh = Color(0xFF3B2C21),
    surfaceContainerHighest = Color(0xFF49372B),
    outline = Color(0xFFB79E8C),
    outlineVariant = Color(0xFF564337),
    inverseSurface = Color(0xFFF8EADD),
    inverseOnSurface = Color(0xFF302217),
    inversePrimary = Color(0xFF964906),
)

internal fun Skin.colors(dark: Boolean): ColorScheme = when (this) {
    Skin.Green -> if (dark) greenDark else greenLight
    Skin.Purple -> if (dark) purpleDark else purpleLight
    Skin.Orange -> if (dark) orangeDark else orangeLight
}

internal data class ArtworkColors(val background: Color, val center: Color, val accent: Color)

private val greenArtwork = ArtworkColors(Color(0xFF244B43), Color(0xFF17352D), Color(0xFFA4F4CD))
private val purpleArtwork = ArtworkColors(Color(0xFF512879), Color(0xFF371460), Color(0xFFDDBAFF))
private val orangeArtwork = ArtworkColors(Color(0xFF733500), Color(0xFF512400), Color(0xFFFFB77C))
internal val LocalArtworkColors = staticCompositionLocalOf { greenArtwork }

private val typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 42.sp, lineHeight = 48.sp, letterSpacing = (-1.8).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 29.sp, lineHeight = 35.sp, letterSpacing = (-0.6).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp,
    ),
)

@Composable
internal fun ResonTheme(skin: Skin, content: @Composable () -> Unit) {
    val artworkColors = when (skin) {
        Skin.Green -> greenArtwork
        Skin.Purple -> purpleArtwork
        Skin.Orange -> orangeArtwork
    }
    CompositionLocalProvider(LocalArtworkColors provides artworkColors) {
        MaterialTheme(
            colorScheme = skin.colors(isSystemInDarkTheme()),
            typography = typography,
            content = content,
        )
    }
}
