package app.motionwall.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.motionwall.R

// Palette (spec): calm, premium, wallpaper-as-hero.
val Indigo = Color(0xFF4F46E5)
val Purple = Color(0xFF8B5CF6)
val Bg = Color(0xFF0B0B0E)
val Card = Color(0xFF15161B)
val TextPrimary = Color(0xFFECECF2)
val TextSecondary = Color(0xFFA2A2B5)
val Success = Color(0xFF30D158)

private val Inter = FontFamily(
    Font(R.font.inter, FontWeight.Normal),
    Font(R.font.inter, FontWeight.Medium),
    Font(R.font.inter, FontWeight.SemiBold),
    Font(R.font.inter, FontWeight.Bold),
)

private val scheme = darkColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    secondary = Purple,
    onSecondary = Color.White,
    tertiary = Purple,
    background = Bg,
    onBackground = TextPrimary,
    surface = Card,
    onSurface = TextPrimary,
    surfaceVariant = Card,
    onSurfaceVariant = TextSecondary,
    outline = Color(0x1FFFFFFF),
)

private fun typography(): Typography {
    fun s(size: Int, weight: FontWeight, lh: Int, spacing: Double = 0.0) =
        TextStyle(fontFamily = Inter, fontSize = size.sp, fontWeight = weight,
            lineHeight = lh.sp, letterSpacing = spacing.sp)
    return Typography(
        displaySmall = s(34, FontWeight.Bold, 40, (-0.5)),
        headlineMedium = s(26, FontWeight.SemiBold, 32, (-0.3)),
        titleLarge = s(20, FontWeight.SemiBold, 26),
        titleMedium = s(16, FontWeight.SemiBold, 22),
        bodyLarge = s(16, FontWeight.Normal, 24),
        bodyMedium = s(14, FontWeight.Medium, 20),
        bodySmall = s(12, FontWeight.Medium, 16),
        labelLarge = s(16, FontWeight.SemiBold, 20),
    )
}

private val shapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MotionWallTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography(), shapes = shapes, content = content)
}
