/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.opentv.R
import androidx.compose.ui.unit.sp

/**
 * "Obsidian Stream" — the StreamFlow-style look: a deep obsidian canvas, glassy charcoal cards,
 * electric cyan for focus/live progress, vivid violet for category tags and neon magenta kept
 * strictly for LIVE / recording badges. Still near-black overall, so it stays easy on the eyes
 * in a dark living room; the saturated colours only ever appear as small accents.
 */
object Obsidian {
    val Canvas = Color(0xFF0B0F14)
    val Surface = Color(0xFF141A23)
    val Elevated = Color(0xFF1E2633)
    val Cyan = Color(0xFF00F2FE)
    val CyanTint = Color(0x1F00F2FE) // 12 % cyan — active chips, selected rows
    val CyanGlow = Color(0x5900F2FE) // 35 % cyan — the "neon" ring around focused cards
    val Violet = Color(0xFF7928CA)
    val VioletDeep = Color(0xFF6807BA)
    val Magenta = Color(0xFFFF007A)
    val TextHigh = Color(0xFFF2F4F8)
    val TextMid = Color(0xFF94A3B8)
    val TextLow = Color(0xFF64748B)
    val Hairline = Color(0x14FFFFFF) // 8 % white — the 1px borders on cards
}

private val DarkScheme = darkColorScheme(
    primary = Obsidian.Cyan,
    onPrimary = Obsidian.Canvas,
    primaryContainer = Color(0xFF0E3A40),
    onPrimaryContainer = Color(0xFFE0FDFF),
    secondary = Obsidian.Violet,
    onSecondary = Color.White,
    secondaryContainer = Obsidian.VioletDeep,
    onSecondaryContainer = Color(0xFFEFDBFF),
    tertiary = Obsidian.Magenta,
    onTertiary = Color.White,
    background = Obsidian.Canvas,
    onBackground = Obsidian.TextHigh,
    surface = Obsidian.Surface,
    onSurface = Obsidian.TextHigh,
    surfaceVariant = Obsidian.Elevated,
    onSurfaceVariant = Obsidian.TextMid,
    outline = Color(0xFF2A3445),
    outlineVariant = Obsidian.Hairline,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/**
 * Light mode for anyone who wants it. By default TV boxes stay dark (a white living-room
 * screen at night is nobody's friend) — that default lives at the call site, so a user who
 * explicitly picks Light in settings gets it on any device.
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF3B4FCC),
    onPrimary = Color.White,
    background = Color(0xFFFBFBFE),
    onBackground = Color(0xFF13141A),
    surface = Color.White,
    onSurface = Color(0xFF13141A),
    surfaceVariant = Color(0xFFEEF0F6),
    onSurfaceVariant = Color(0xFF4A4F60),
    outline = Color(0xFFD3D7E2),
)

/**
 * Headlines in Plus Jakarta Sans, everything else in Inter — the design's two families. Both ship
 * as variable fonts (fetched into res/font by scripts/fetch-fonts.sh / CI), so each weight below
 * pins the variable axis explicitly. Sizes stay scaled up for ten-foot viewing.
 */
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private val Jakarta = FontFamily(
    variable(R.font.plus_jakarta_sans, 600),
    variable(R.font.plus_jakarta_sans, 700),
    variable(R.font.plus_jakarta_sans, 800),
)

private val Inter = FontFamily(
    variable(R.font.inter, 400),
    variable(R.font.inter, 500),
    variable(R.font.inter, 600),
    variable(R.font.inter, 700),
)

private val OpenTvTypography = Typography(
    displaySmall = TextStyle(fontFamily = Jakarta, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Jakarta, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = Jakarta, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = Jakarta, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = Jakarta, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = Inter, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = Inter, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
)

@Composable
fun OpenTvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = OpenTvTypography,
        content = content,
    )
}
