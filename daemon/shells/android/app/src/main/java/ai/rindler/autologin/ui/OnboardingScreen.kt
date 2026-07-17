package ai.rindler.autologin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** A crafted hero emblem: concentric emerald halos (soft glow) under a crisp ring,
 *  with the slide icon larger and centered. Reads as intentional art, not a stock
 *  glyph dropped into a flat circle. */
@Composable
private fun OnboardingArt(icon: ImageVector) {
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.size(184.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = size.minDimension / 2f
            drawCircle(primary.copy(alpha = 0.05f), radius = c)          // soft outer glow
            drawCircle(primary.copy(alpha = 0.09f), radius = c * 0.72f)  // mid glow
            drawCircle(primary.copy(alpha = 0.14f), radius = c * 0.46f)  // inner disc under icon
            drawCircle(                                                  // crisp defining ring
                primary.copy(alpha = 0.22f),
                radius = c * 0.72f,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        Icon(icon, null, tint = primary, modifier = Modifier.size(58.dp))
    }
}

private data class Slide(val icon: ImageVector, val title: String, val body: String)

private val slides = listOf(
    Slide(
        Icons.Rounded.Shield,
        "Stored on your phone",
        "Credentials are encrypted at rest in this device's secure storage. The hub doesn't keep a hosted credential copy.",
    ),
    Slide(
        Icons.Rounded.Bolt,
        "Hands-free once you're set up",
        "Set up once, then close the app. When one of your sign-ins needs a credential, your phone releases only the requested username, password or one-time code — automatically, no tap needed.",
    ),
    Slide(
        Icons.Rounded.CloudOff,
        "Only for a verified request",
        "Your phone releases a credential only to a request it cryptographically verifies is from the hub, for your account and that exact sign-in — and seals it end-to-end to that login's single-use key. It transits the hub in memory and is never stored in its vault.",
    ),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pager = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val last = pager.currentPage == slides.lastIndex

    // Back steps to the previous slide instead of exiting the app.
    BackHandler(enabled = pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .padding(horizontal = 28.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDone) {
                Text("Skip", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            }
        }

        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            val s = slides[page]
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Bias the block just above center: a touch more room below than above
                // so the eye lands on the emblem, not on empty space.
                Spacer(Modifier.weight(0.82f))
                OnboardingArt(s.icon)
                Spacer(Modifier.height(40.dp))
                Text(
                    s.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = cs.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    s.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.weight(1f))
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(slides.size) { i ->
                val active = pager.currentPage == i
                val w by animateDpAsState(if (active) 22.dp else 7.dp, label = "w")
                val c by animateColorAsState(if (active) cs.primary else cs.outline, label = "c")
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .height(7.dp)
                        .width(w)
                        .clip(CircleShape)
                        .background(c),
                )
            }
        }

        PrimaryButton(
            text = if (last) "Get started" else "Continue",
            onClick = {
                if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            },
            modifier = Modifier.padding(bottom = 20.dp),
        )
    }
}
