// SetupScreen — the post-pairing "Finish setting up" checklist.
//
// ONE screen with REAL inline toggles, not a carousel: tutorials get swiped past,
// checklists get activated. Each row IS the permission primer, so the OS prompt fires
// only AFTER the user has flipped OUR switch and knows what it buys them. Skippable and
// never blocking — every exit path (Done, "Not now", system back) writes K_SETUP_SEEN so
// this interstitial can never loop.
//
// Two chromes, one body:
//   fromHome = false — the interstitial. Custom Column (NOT the AppScreen pushed
//     overload) because the CTA must stay PINNED below a scrolling middle, and a bare
//     56dp top zone carries the "Not now" skip.
//   fromHome = true  — the pushed screen reached from the Home nudge row. Standard
//     AppScreen/TopBar with a back arrow, and a "Don't remind me" action that retires the
//     nudge for good.

package ai.rindler.autologin.ui

import ai.rindler.autologin.KeystoreSecretSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private const val SETUP_TITLE = "Finish setting up"
private const val SETUP_BODY =
    "These switches make sign-ins hands-free and dependable. You can change them anytime in Settings."

/**
 * The checklist. [fromHome] selects the chrome (interstitial vs pushed); [onDone] is the
 * single exit callback, and EVERY path into it has already written setSetupSeen().
 */
@Composable
fun SetupScreen(
    store: KeystoreSecretSource,
    fromHome: Boolean = false,
    onDone: () -> Unit,
) {
    // One exit funnel: whichever affordance the user takes, the flag is written before we
    // navigate, so a process death right after cannot resurrect the interstitial.
    fun exit() {
        store.setSetupSeen()
        onDone()
    }

    if (fromHome) {
        AppScreen(
            topBar = { scrolled ->
                TopBar(
                    title = SETUP_TITLE,
                    onBack = { exit() },
                    scrolled = scrolled,
                    action = {
                        // Retires the Home nudge permanently — the user has told us they
                        // are done being reminded, and we take that literally.
                        TextButton(onClick = {
                            store.setSetupNudgeDismissed()
                            exit()
                        }) {
                            Text(
                                "Don't remind me",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    },
                )
            },
            footer = true,
        ) {
            SetupBody(store)
        }
        return
    }

    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .background(cs.surface),
    ) {
        // Top zone (56dp) — "Not now" is first in traversal order so a screen-reader user
        // meets the escape hatch before the switches.
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { exit() }) {
                Text("Not now", color = cs.primary, style = MaterialTheme.typography.labelLarge)
            }
        }

        // Scrolling middle: hero + the live rows. Scrolls (rather than compressing) so the
        // whole checklist stays reachable at fontScale 2.0.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll),
        ) {
            SetupBody(store)
        }

        // Pinned CTA + trust line — never scroll away from the way out.
        PrimaryButton(
            text = "Done",
            onClick = { exit() },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        TrustFooter()
    }
}

/**
 * Hero + the four toggles, shared verbatim by both chromes so the pushed screen and the
 * interstitial can never drift apart. Rows are full-bleed floats: no dividers, no cards,
 * no SectionHeader — the checklist reads as one list, not four sections.
 */
@Composable
private fun ColumnScope.SetupBody(store: KeystoreSecretSource) {
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(8.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(Icons.Rounded.Checklist, 56.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            SETUP_TITLE,
            style = MaterialTheme.typography.headlineMedium,
            color = cs.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            SETUP_BODY,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
    Spacer(Modifier.height(24.dp))

    // Ordered by what a stalled login needs first: read the code, stay alive to receive
    // it, then the optional network + visibility opt-ins.
    SmsAutoReadToggle(store)
    BatteryToggle()
    EgressToggle(store)
    NotificationToggle() // self-hides when there is nothing left to ask for
    Spacer(Modifier.height(8.dp))
}
