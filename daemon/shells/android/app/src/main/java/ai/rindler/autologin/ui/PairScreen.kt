package ai.rindler.autologin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// The first-run sign-in screen — the Tailscale IntroView shape: one hero badge, a headline,
// a plain-language promise, and a single "Sign in" action. Tapping "Sign in" opens the
// Rindler sign-in page (openSignInEnroll) in a Chrome Custom Tab; the device auto-enrolls
// when the browser redirects back (handled by MainActivity -> AutoLoginApp). Self-hosters
// use the discreet "Use a self-hosted server" link -> Advanced to pair against their own
// server with a code. No pairing codes or server fields clutter this screen.
@Composable
fun PairScreen(onAdvanced: () -> Unit) {
    val ctx = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The hero + "Sign in" block scrolls when it does not fit (a large font scale), so
        // the single sign-in action — on the FIRST screen every user meets — can never be
        // pushed off screen. It stays vertically centred while it fits (Arrangement.Center
        // over a min height of one viewport), the same idiom AppScreen uses. The self-hosted
        // link and trust footer stay pinned below the scroll region.
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val minH = maxHeight
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .heightIn(min = minH),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                IconBadge(Icons.Rounded.Shield, 72.dp)
                Spacer(Modifier.height(40.dp))
                Text(
                    "Sign in to Rindler",
                    style = MaterialTheme.typography.headlineMedium,
                    color = cs.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    // Do NOT restore an "we only ever see X / never Y" absolute here. The previous
                    // wording claimed we only ever see the email, on the screen immediately before
                    // the user types their Rindler password into a browser — while the app in fact
                    // also receives their account photo and this phone's name, and saved passwords
                    // do leave the phone (sealed) during a sign-in. Say what is actually shared.
                    "You'll finish signing in with your browser, which links this phone to your " +
                        "Rindler account. That shares your account details: your email, your photo, " +
                        "and this phone's name. The site passwords you save here stay encrypted on " +
                        "this phone, and our servers can't read them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                Spacer(Modifier.height(32.dp))
                PrimaryButton(
                    text = "Sign in",
                    onClick = {
                        failed = false
                        if (!openSignInEnroll(ctx)) failed = true
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                AnimatedVisibility(failed) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        StatusLine(StatusKind.Error, "Couldn't open a browser. Check that you have one installed and enabled, then try again.")
                    }
                }
            }
        }
        TextButton(onClick = onAdvanced) {
            Text(
                "Use a self-hosted server",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        TrustFooter(Modifier.fillMaxWidth().padding(bottom = 16.dp))
    }
}
