package ai.rindler.autologin.ui

import ai.rindler.autologin.ui.theme.LocalReducedMotion
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
        "Your saved logins are stored encrypted on this phone. Rindler's servers don't keep a copy.",
    ),
    Slide(
        Icons.Rounded.Bolt,
        "Hands-free once you're set up",
        "Set up once, then close the app. When one of your sign-ins needs a credential, your phone releases only the requested username, password or one-time code, automatically in the background. If a texted code ever isn't picked up, you can type it in yourself.",
    ),
    Slide(
        Icons.Rounded.CloudOff,
        "Only for a verified request",
        "Your phone only answers requests it can verify came from your own Rindler sign-in. Each credential is sealed for that one sign-in, so no server or network in between can read it — it's opened only by the worker doing your sign-in, used once, and never stored.",
    ),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pager = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val last = pager.currentPage == slides.lastIndex
    // Reduced motion: jump between slides instantly instead of the horizontal slide.
    val reduced = LocalReducedMotion.current
    suspend fun goToPage(page: Int) {
        if (reduced) pager.scrollToPage(page) else pager.animateScrollToPage(page)
    }

    // Back steps to the previous slide instead of exiting the app.
    BackHandler(enabled = pager.currentPage > 0) {
        scope.launch { goToPage(pager.currentPage - 1) }
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

        // The pager carries ONLY illustration + text; dots + CTA are fixed chrome. Each
        // slide scrolls when its body copy does not fit (long copy at ~1.3 font scale on a
        // narrow phone clipped unreadably before), staying vertically centred while it fits
        // (Arrangement.Center over a min height of one page), the idiom AppScreen uses.
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            val s = slides[page]
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val minH = maxHeight
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = minH),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
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
                }
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
                if (last) onDone() else scope.launch { goToPage(pager.currentPage + 1) }
            },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        )
    }
}
