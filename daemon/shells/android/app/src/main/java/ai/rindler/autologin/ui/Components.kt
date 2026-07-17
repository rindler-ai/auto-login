package ai.rindler.autologin.ui

import android.net.Uri
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

/** Filled emerald CTA: 54dp, bold, gentle press-scale, inline loading spinner. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && enabled) 0.98f else 1f, label = "press")
    val cs = MaterialTheme.colorScheme
    // Disabled affordance is theme-split. In LIGHT a dimmed pale-emerald reads
    // unmistakably as "the action, not yet ready." Over near-black that same tint
    // stays a dark-green block that can read as an enabled-but-dim button, so DARK
    // uses a neutral low-opacity veil + clearly-dim label — obviously inactive.
    val dark = isSystemInDarkTheme()
    val disabledBg = if (dark) cs.onSurface.copy(alpha = 0.10f) else cs.primary.copy(alpha = 0.30f)
    val disabledContent = if (dark) cs.onSurface.copy(alpha = 0.45f) else cs.onPrimary.copy(alpha = 0.80f)
    Surface(
        onClick = { if (!loading) onClick() },
        enabled = enabled && !loading,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.large,
        color = if (enabled) cs.primary else disabledBg,
        contentColor = if (enabled) cs.onPrimary else disabledContent,
        modifier = modifier
            .scale(scale)
            .fillMaxWidth()
            .height(54.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(
                    strokeWidth = 2.4.dp,
                    color = cs.onPrimary,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Text(text, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Quiet outlined action for secondary choices. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, label = "press")
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.large,
        color = Color.Transparent,
        contentColor = cs.onSurface,
        border = BorderStroke(1.dp, cs.outline),
        modifier = modifier
            .scale(scale)
            .fillMaxWidth()
            .height(54.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke(this)
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** OutlinedTextField restyled to the theme (rounded, calm focus, our colors). */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    singleLine: Boolean = true,
    isError: Boolean = false,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    // Filled fill in dark so fields stand out from the near-black bg; clean white in
    // light. Border brightens on focus; turns red on error.
    val container = if (isSystemInDarkTheme()) cs.surfaceContainerHigh else cs.surface
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        isError = isError,
        enabled = enabled,
        textStyle = textStyle,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        trailingIcon = trailingIcon,
        supportingText = supportingText?.let { { Text(it) } },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = cs.primary,
            unfocusedBorderColor = cs.outline,
            errorBorderColor = cs.error,
            focusedLabelColor = cs.primary,
            unfocusedLabelColor = cs.onSurfaceVariant,
            errorLabelColor = cs.error,
            cursorColor = cs.primary,
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            errorContainerColor = container,
        ),
    )
}

/** A soft, bordered surface card (1dp outline, no heavy shadow). */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.fillMaxWidth(),
    ) { content() }
}

/** A softly-pulsing status dot (the "live" signal). */
@Composable
fun PulseDot(color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "pulse")
    val alpha by t.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100)), label = "a",
    )
    Box(modifier.size(9.dp)) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .graphicsLayer { this.alpha = alpha }
                .background(color),
        )
    }
}

// A muted, premium palette for per-site avatars (deterministic by domain).
private val avatarTints = listOf(
    Color(0xFF0E8466), // emerald
    Color(0xFF2563C9), // blue
    Color(0xFF7C4DD6), // violet
    Color(0xFFC2410C), // rust
    Color(0xFFB4315E), // rose
    Color(0xFF0E7490), // teal
    Color(0xFF9A7A0E), // gold
)

/** A distinct per-site avatar: the domain's first letter over a tint derived from
 *  the domain (so every site is visually different — better than one globe). */
@Composable
fun SiteAvatar(site: String, size: Int = 44) {
    val clean = site.trim().removePrefix("www.")
    val letter = clean.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    val tint = avatarTints[((clean.hashCode() % avatarTints.size) + avatarTints.size) % avatarTints.size]
    Box(
        Modifier.size(size.dp).clip(MaterialTheme.shapes.medium).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = tint, style = MaterialTheme.typography.titleMedium)
    }
}

/** A site's real favicon/logo, resolved from the SAME favicon service
 *  your hub uses (the web app `the web app's icon component`): Google's S2 favicons first,
 *  then DuckDuckGo's icon service. This means a newly-added site shows
 *  its correct logo automatically — no per-site asset to ship. Sized and rounded
 *  to match [SiteAvatar]; the letter-tile [SiteAvatar] is the loading/error
 *  fallback, so a favicon that never loads degrades to the nice avatar rather
 *  than a broken image. */
@Composable
fun SiteLogo(domain: String, size: Int = 44, warning: Boolean = false) {
    if (!warning) {
        SiteFavicon(domain, size)
        return
    }
    // Unsupported site: the login is savable, but we badge its favicon with an amber
    // warning so the user knows the hub hasn't mapped it yet. Passing warning=false
    // (once the site is mapped) resolves it back to the plain favicon.
    Box {
        SiteFavicon(domain, size)
        val badge = (size * 0.42f).coerceAtLeast(14f)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(badge.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface) // ring so it reads over any favicon
                .padding(1.5.dp)
                .clip(CircleShape)
                .background(warningAmber),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.PriorityHigh,
                contentDescription = "Not supported yet",
                tint = Color.White,
                modifier = Modifier.size((badge * 0.7f).dp),
            )
        }
    }
}

// Amber warning accent for the unsupported-site favicon badge. A fixed color (not a
// theme role) because Material3 has no "warning" role and this must read the same in
// light and dark.
private val warningAmber = Color(0xFFF59E0B)

/** The favicon itself (no badge): Google S2 then DuckDuckGo, falling back to the
 *  monogram avatar. Extracted so [SiteLogo] can overlay a warning badge. */
@Composable
private fun SiteFavicon(domain: String, size: Int) {
    val clean = domain.trim().removePrefix("www.")
    // Ordered favicon sources, mirroring faviconSources() in the web app's icon component.
    val sources = remember(clean) {
        val enc = Uri.encode(clean)
        listOf(
            "https://www.google.com/s2/favicons?domain=$enc&sz=64",
            "https://icons.duckduckgo.com/ip3/$enc.ico",
        )
    }
    // Index into `sources`; each load error advances to the next. Past the end we
    // render the monogram avatar directly (the same fallback the web catalog uses).
    var idx by remember(clean) { mutableStateOf(0) }
    if (idx >= sources.size) {
        SiteAvatar(domain, size)
        return
    }
    SubcomposeAsyncImage(
        model = sources[idx],
        contentDescription = null,
        modifier = Modifier.size(size.dp).clip(MaterialTheme.shapes.medium),
        contentScale = ContentScale.Fit,
        // While loading and on the final error, show the avatar — never a blank
        // box or broken-image glyph.
        loading = { SiteAvatar(domain, size) },
        error = { SiteAvatar(domain, size) },
        onError = { idx += 1 },
    )
}

/** A quiet, centered trust line — anchors the bottom of a screen and reinforces
 *  the one promise that matters for this app. */
@Composable
fun TrustFooter(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            "Encrypted at rest · releases are end-to-end encrypted",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A minimal top bar: optional back, title, optional trailing action. */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            Spacer(Modifier.width(6.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = if (onBack != null) 2.dp else 6.dp),
        )
        action?.invoke()
    }
}

/** Small circular tinted icon chip used in list rows / headers. */
@Composable
fun IconChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = MaterialTheme.colorScheme.primary,
    // primary-at-alpha (not primaryContainer) so the tinted circle is the SAME hue
    // in light + dark — the accent never drifts between themes.
    bg: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
    size: Int = 40,
) {
    Box(
        Modifier.size(size.dp).clip(MaterialTheme.shapes.medium).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.5).dp))
    }
}
