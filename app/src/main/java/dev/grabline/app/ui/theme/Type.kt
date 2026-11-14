package dev.grabline.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.grabline.app.R

val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

val PlexMono = FontFamily(Font(R.font.ibm_plex_mono_regular, FontWeight.Normal))

/** For sizes, speeds and versions. */
val MonoNumbers = TextStyle(
    fontFamily = PlexMono,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 20.sp,
)

private val base = Typography()

val GrabLineTypography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = PlexSans),
    displayMedium = base.displayMedium.copy(fontFamily = PlexSans),
    displaySmall = base.displaySmall.copy(fontFamily = PlexSans),
    headlineLarge = base.headlineLarge.copy(fontFamily = PlexSans),
    headlineMedium = base.headlineMedium.copy(fontFamily = PlexSans),
    headlineSmall = base.headlineSmall.copy(fontFamily = PlexSans),
    titleLarge = base.titleLarge.copy(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = PlexSans, fontWeight = FontWeight.Medium),
    titleSmall = base.titleSmall.copy(fontFamily = PlexSans, fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontFamily = PlexSans),
    bodyMedium = base.bodyMedium.copy(fontFamily = PlexSans),
    bodySmall = base.bodySmall.copy(fontFamily = PlexSans),
    labelLarge = base.labelLarge.copy(fontFamily = PlexSans, fontWeight = FontWeight.Medium),
    labelMedium = base.labelMedium.copy(fontFamily = PlexSans, fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontFamily = PlexSans, fontWeight = FontWeight.Medium),
)
