package app.viki.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val StudioBackground = Color(0xFF1E1F22)
val StudioSurface = Color(0xFF2B2D30)
val StudioSurfaceElevated = Color(0xFF313438)
val StudioBorder = Color(0xFF3E4147)
val StudioPrimary = Color(0xFF568AF2)
val StudioPrimaryContainer = Color(0xFF253961)
val StudioOnPrimary = Color(0xFFFFFFFF)
val StudioSecondary = Color(0xFF3DDC84) // Android Green accent
val StudioSecondaryContainer = Color(0xFF1A3D2B)
val StudioTextPrimary = Color(0xFFDFE1E5)
val StudioTextSecondary = Color(0xFF9DA0A8)
val StudioWarning = Color(0xFFF2C55C)
val StudioError = Color(0xFFF75464)

private val VikiDarkColorScheme =
  darkColorScheme(
    primary = StudioPrimary,
    onPrimary = StudioOnPrimary,
    primaryContainer = StudioPrimaryContainer,
    onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = StudioSecondary,
    onSecondary = Color(0xFF062916),
    secondaryContainer = StudioSecondaryContainer,
    onSecondaryContainer = Color(0xFFA7F5C7),
    background = StudioBackground,
    onBackground = StudioTextPrimary,
    surface = StudioSurface,
    onSurface = StudioTextPrimary,
    surfaceVariant = StudioSurfaceElevated,
    onSurfaceVariant = StudioTextSecondary,
    outline = StudioBorder,
    error = StudioError,
  )

private val VikiShapes =
  Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
  )

private val VikiTypography =
  Typography(
    headlineMedium =
      TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
      ),
    titleLarge =
      TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
      ),
    titleMedium =
      TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
      ),
    bodyLarge =
      TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
      ),
    bodyMedium =
      TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
      ),
    labelMedium =
      TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
      ),
  )

@Composable
fun VikiTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = VikiDarkColorScheme,
    shapes = VikiShapes,
    typography = VikiTypography,
    content = content,
  )
}
