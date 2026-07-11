package app.motionwall.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Apple-AirPods-style apply confirmation: scrim fades up, "Applying…", then a checkmark
 * springs in, "Done" — no spinner. ~1100ms total. Calls onComplete after the sequence.
 */
@Composable
fun ApplyOverlay(active: Boolean, onComplete: () -> Unit) {
    // phase: 0 hidden, 1 applying, 2 done
    var phase by remember { mutableIntStateOf(0) }

    LaunchedEffect(active) {
        if (active) {
            phase = 1
            delay(650)
            phase = 2
            delay(750)
            phase = 0
            onComplete()
        }
    }

    val scrim by animateFloatAsState(if (phase == 0) 0f else 1f, tween(300), label = "scrim")
    if (scrim == 0f && phase == 0) return

    val check by animateFloatAsState(
        if (phase == 2) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "check"
    )

    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = scrim }.background(Bg.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (phase == 2) {
                Icon(Icons.Rounded.CheckCircle, null, tint = Success,
                    modifier = Modifier.size(88.dp).scale(check))
            }
            Text(
                if (phase == 2) "Done" else "Applying…",
                color = TextPrimary,
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
