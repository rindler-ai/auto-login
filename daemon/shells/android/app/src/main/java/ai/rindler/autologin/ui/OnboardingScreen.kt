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

/** A crafted hero emblem: concentric emerald halos (STATIC — no loops) under a crisp
 *  ring, with the slide icon carried by the shared [IconBadge] as the inner core. Reads
 *  as intentional art, not a stock glyph dropped into a flat circle. */
@Composable
private fun OnboardingArt(icon: ImageVector) {
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.size(184.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = size.minDimension / 2f
            drawCircle(primary.copy(alpha = 0.05f), radius = c)          // soft outer glow
            drawCircle(primary.copy(alpha = 0.09f), radius = c * 0.72f)  // mid glow
            drawCircle(                                                  // crisp defining ring
                primary.copy(alpha = 0.22f),
                radius = c * 0.72f,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        IconBadge(icon, 72.dp)
    }
}

private data class Slide(val icon: ImageVector, val title: String, val body: String)

private val slides = listOf(
    Slide(
        Icons.Rounded.Shield,
        "Stored on your phone",
        "Credentials are encrypted at rest in this device's secure storage. The server doesn't keep a hosted credential copy.",
    ),
    Slide(
        Icons.Rounded.Bolt,
        "Hands-free once you're set up",
        "Set up once, then close the app. When one of your sign-ins needs a credential, your phone releases only the requested username, password or one-time code — automatically, no tap needed.",
    ),
    Slide(
        Icons.Rounded.CloudOff,
        "Only for a verified request",
        "Your phone releases a credential only to a request it cryptographically verifies is from the server — for your account and that exact sign-in — sealed end-to-end to that login's single-use key so the server can never read it.",
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
            .background(cs.surface),
    ) {
        // Top zone (56dp) — Skip is an interactive affordance (accent budget → primary),
        // hidden on the final slide where the CTA reads "Get started".
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!last) {
                TextButton(onClick = onDone) {
                    Text("Skip", color = cs.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // The pager carries ONLY illustration + text; dots + CTA are fixed chrome.
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            val s = slides[page]
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                OnboardingArt(s.icon)
                Spacer(Modifier.height(32.dp))
                Text(
                    s.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = cs.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    s.body,
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                Spacer(Modifier.weight(1f))
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(slides.size) { i ->
                val active = pager.currentPage == i
                val w by animateDpAsState(if (active) 22.dp else 8.dp, label = "w")
                val c by animateColorAsState(
                    if (active) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.3f),
                    label = "c",
                )
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .height(8.dp)
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
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        )
    }
}
