package ai.rindler.autologin.ui

import ai.rindler.autologin.ConnectionStatus
import ai.rindler.autologin.ui.theme.LocalExtendedColors
import ai.rindler.autologin.ui.theme.LocalReducedMotion
import ai.rindler.autologin.ui.theme.MotionTokens
import ai.rindler.autologin.ui.theme.Mono
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage

// ─────────────────────────────────────────────────────────────────────────────
// Shared component kit (redesign §2). One accent (primary), used ONLY as an
// interactive affordance; status is always word + dot + color; float-not-box for
// resting content; the single AppScreen scaffold kills the two scroll models.
// C2: a hero IconBadge is a sanctioned accent-budget use (identity/brand moment),
// alongside the interactive affordances — it is NOT decorative row tinting.
// ─────────────────────────────────────────────────────────────────────────────

private const val TINT_MS = 120 // surface → surfaceContainer scroll tint

/**
 * 1. AppScreen — the single scaffold: pinned top bar → scrolling middle → optional
 * pinned [TrustFooter]. `topBar` receives whether the content has scrolled (so it
 * can tint). Content gets NO default horizontal padding: rows are full-bleed and
 * text/field blocks pad themselves 16dp.
 */
@Composable
fun AppScreen(
    topBar: @Composable (scrolled: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    footer: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Column(modifier.fillMaxSize()) {
        topBar(scroll.value > 0)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll),
            content = content,
        )
        if (footer) TrustFooter()
    }
}

/** Convenience overload for pushed screens — [TopBar] with a back arrow + title. */
@Composable
fun AppScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    footer: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppScreen(
        topBar = { scrolled -> TopBar(title = title, onBack = onBack, scrolled = scrolled) },
        modifier = modifier,
        footer = footer,
        content = content,
    )
}

/**
 * 2. TopBar — M3 small bar, 64dp, arrow-back in a 48dp target, title `titleLarge`
 * matching the row label that navigated here; `surface` at rest → `surfaceContainer`
 * on scroll (120ms). Neutral, never colored.
 */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    scrolled: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        targetValue = if (scrolled) cs.surfaceContainer else cs.surface,
        animationSpec = tween(TINT_MS),
        label = "topbar-tint",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .height(64.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = cs.onSurface,
                )
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = cs.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack != null) 2.dp else 8.dp),
        )
        action?.invoke()
        Spacer(Modifier.width(4.dp))
    }
}

/**
 * 3. AccountHeader — IS the Home top bar (Tailscale `MainView` header). Switch LEFT
 * (disabled while toggling — never an optimistic flip), identity MIDDLE (email over
 * dot+status, single truthful status line), 40dp Avatar RIGHT (tap → Settings; gear
 * only appears signed out, which this app never is once paired). Tints on scroll.
 */
@Composable
fun AccountHeader(
    email: String?,
    avatarUrl: String?,
    status: ConnectionStatus,
    serviceEnabled: Boolean,
    toggleInFlight: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenSettings: (() -> Unit)?,
    scrolled: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val ext = LocalExtendedColors.current
    val bg by animateColorAsState(
        targetValue = if (scrolled) cs.surfaceContainer else cs.surface,
        animationSpec = tween(TINT_MS),
        label = "header-tint",
    )

    // Status line — the truth (the Switch reflects the *requested* state instead).
    val statusText: String
    val statusColor: Color
    val showWarnIcon: Boolean
    when (status) {
        ConnectionStatus.CONNECTED -> {
            statusText = "Connected"; statusColor = ext.statusConnected; showWarnIcon = false
        }
        ConnectionStatus.CONNECTING -> {
            statusText = "Connecting…"; statusColor = cs.onSurfaceVariant; showWarnIcon = false
        }
        ConnectionStatus.PAUSED -> {
            statusText = "Paused"; statusColor = cs.onSurfaceVariant; showWarnIcon = false
        }
        ConnectionStatus.OFFLINE_RETRYING -> {
            statusText = "Offline — retrying"; statusColor = ext.warning; showWarnIcon = true
        }
        ConnectionStatus.ACTION_NEEDED -> {
            statusText = "Action needed"; statusColor = cs.error; showWarnIcon = false
        }
    }
    val line1 = email ?: "Signed in"

    Row(
        modifier
            .fillMaxWidth()
            .background(bg)
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // LEFT — the pause/resume Switch (48dp target, never an optimistic flip).
        Switch(
            checked = serviceEnabled,
            onCheckedChange = onToggle,
            enabled = !toggleInFlight,
        )
        Spacer(Modifier.width(16.dp))

        // MIDDLE — email over dot + status.
        Column(Modifier.weight(1f)) {
            Text(
                line1,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (email != null) Modifier.semantics { contentDescription = email } else Modifier,
            )
            Row(
                Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(status == ConnectionStatus.CONNECTING, statusColor)
                Spacer(Modifier.width(6.dp))
                Text(statusText, style = MaterialTheme.typography.bodyMedium, color = statusColor)
                if (showWarnIcon) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))

        // RIGHT — 40dp avatar in a 48dp tap target → Settings.
        val avatarBox = Modifier
            .size(48.dp)
            .then(if (onOpenSettings != null) Modifier.clickable(onClick = onOpenSettings) else Modifier)
        Box(avatarBox, contentAlignment = Alignment.Center) {
            if (email == null) {
                IconBadge(Icons.Rounded.Shield, 40.dp)
            } else {
                Avatar(url = avatarUrl, fallbackText = email, size = 40.dp)
            }
        }
    }
}

/** 8dp status dot; pulses opacity 1→0.6 only while [connecting] (else still). */
@Composable
private fun StatusDot(connecting: Boolean, color: Color) {
    val animatedColor by animateColorAsState(color, tween(MotionTokens.short), label = "dot-color")
    val reduced = LocalReducedMotion.current
    val alpha = if (connecting && !reduced) {
        val t = rememberInfiniteTransition(label = "dot-pulse")
        val a by t.animateFloat(
            initialValue = 1f,
            targetValue = 0.6f,
            animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
            label = "dot-alpha",
        )
        a
    } else {
        1f
    }
    Box(
        Modifier
            .size(8.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(animatedColor),
    )
}

/**
 * 4. SectionHeader — `labelMedium` caps +0.08em `onSurfaceVariant`; start 16 / top 24
 * / bottom 8. Optional `trailing` count right-aligned at the 16dp end margin. The only
 * taxonomy marker.
 */
@Composable
fun SectionHeader(text: String, trailing: String? = null, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                trailing.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

/** Trailing affordance for a [SettingRow]. */
sealed interface RowTrailing {
    data object None : RowTrailing
    data object Chevron : RowTrailing
    data class Switch(
        val checked: Boolean,
        val onChange: (Boolean) -> Unit,
        val enabled: Boolean = true,
    ) : RowTrailing

    data class Value(val text: String) : RowTrailing
}

/**
 * 5. SettingRow — full-bleed, minHeight 56dp (72dp with supporting), 16dp horizontal
 * padding; 24dp leading icon `onSurfaceVariant` (`error` when danger), 16dp gap; title
 * `bodyLarge` `onSurface`; supporting `bodyMedium onSurfaceVariant`. Ripple ONLY when
 * actionable (never fake affordance); a Switch row toggles from the whole row. Danger =
 * plain red row, never a filled red button.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    supporting: String? = null,
    trailing: RowTrailing = RowTrailing.None,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val contentColor = if (danger) cs.error else cs.onSurface
    val iconColor = if (danger) cs.error else cs.onSurfaceVariant

    // Whole-row action: explicit onClick wins; else a live Switch toggles the row.
    val rowAction: (() -> Unit)? = when {
        !enabled -> null
        onClick != null -> onClick
        trailing is RowTrailing.Switch && trailing.enabled -> {
            { trailing.onChange(!trailing.checked) }
        }
        else -> null
    }
    val minH = if (supporting != null) 72.dp else 56.dp

    // a11y: a switch row is ONE node with ONE action. Without this, TalkBack stops twice
    // (row, then Switch) and reads the state detached from the label it belongs to. When
    // the row's action is the switch itself we use `toggleable(role = Switch)` and merge
    // descendants, so the title + supporting line + on/off state are announced together
    // and the Switch below is rendered inert (onCheckedChange = null) rather than
    // separately focusable. Rows with an explicit onClick keep plain clickable semantics.
    val switchRow = trailing as? RowTrailing.Switch
    val toggleAction = if (onClick == null && switchRow != null && switchRow.enabled) switchRow else null
    val base = modifier
        .fillMaxWidth()
        .then(
            when {
                !enabled -> Modifier
                toggleAction != null -> Modifier
                    .toggleable(
                        value = toggleAction.checked,
                        role = Role.Switch,
                        onValueChange = toggleAction.onChange,
                    )
                    .semantics(mergeDescendants = true) {}
                rowAction != null -> Modifier.clickable(onClick = rowAction)
                else -> Modifier
            },
        )
        .heightIn(min = minH)
        .padding(horizontal = 16.dp)

    Row(base, verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            Icon(leading, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = contentColor)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        when (trailing) {
            RowTrailing.None -> Unit
            RowTrailing.Chevron -> {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
            is RowTrailing.Switch -> {
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = trailing.checked,
                    // Null when the ROW owns the toggle: the control stays visual and the
                    // whole row is the single accessible target (see `toggleAction` above).
                    onCheckedChange = if (toggleAction != null) null else trailing.onChange,
                    enabled = enabled && trailing.enabled,
                )
            }
            is RowTrailing.Value -> {
                Spacer(Modifier.width(8.dp))
                Text(
                    trailing.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 6. MediaRow — 72dp tile-led row (40dp `SiteLogo`/`SiteAvatar`, 16dp gap, `bodyLarge`
 * title + `bodyMedium` supporting, trailing slot). `compact=true` → minHeight 48dp for
 * the Enroll panel (pair with a 32dp logo + `InsetDivider(Dimens.keylineCompact)`).
 * Backs LoginRow / SuggestionRow / RequestMappingRow. `leading` is a slot so the caller
 * supplies the sized tile (G1: a real signature, not a placeholder).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MediaRow(
    title: String,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    supporting: String? = null,
    supportingColor: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    compact: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val minH = if (compact) 48.dp else 72.dp
    val clickable = onClick != null || onLongClick != null
    val base = modifier
        .fillMaxWidth()
        .then(
            if (clickable) {
                Modifier.combinedClickable(
                    onClick = onClick ?: {},
                    onLongClick = onLongClick,
                )
            } else {
                Modifier
            },
        )
        .heightIn(min = minH)
        .padding(horizontal = 16.dp, vertical = 8.dp)

    Row(base, verticalAlignment = Alignment.CenterVertically) {
        leading()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = supportingColor ?: cs.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/**
 * 7. InsetDivider — 1dp `outlineVariant`. Survives ONLY inside Settings' MANAGE group
 * (56dp), inside the Enroll panel (56dp regular / 48dp compact keyline, G2), and
 * full-width (0dp) before the Settings danger zone. NONE between saved-login rows.
 */
@Composable
fun InsetDivider(startIndent: Dp, modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(start = startIndent),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** Kind of a [StatusLine] — drives its icon + color. */
enum class StatusKind { Info, Success, Warning, Error }

/**
 * 8. StatusLine — 16dp icon + 8dp gap + `bodyMedium` in the kind color, vertical pad 8.
 * Success → `statusConnected`; Info → `onSurfaceVariant` (C3, not primary); Warning →
 * `warning`; Error → `error`. Replaces every ErrorRow / StatusRow / InlineStatus.
 */
@Composable
fun StatusLine(kind: StatusKind, text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val ext = LocalExtendedColors.current
    val icon: ImageVector
    val color: Color
    when (kind) {
        StatusKind.Info -> {
            icon = Icons.Rounded.Info; color = cs.onSurfaceVariant
        }
        StatusKind.Success -> {
            icon = Icons.Rounded.CheckCircle; color = ext.statusConnected
        }
        StatusKind.Warning -> {
            icon = Icons.Rounded.WarningAmber; color = ext.warning
        }
        StatusKind.Error -> {
            icon = Icons.Rounded.ErrorOutline; color = cs.error
        }
    }
    Row(
        modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/**
 * 9. IconBadge — `CircleShape`, `primary` 12% bg, icon `primary` at size/2. Sizes 40 /
 * 56 / 72 only (hero moments). A sanctioned accent-budget use (C2), not decorative.
 */
@Composable
fun IconBadge(icon: ImageVector, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(cs.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(size / 2))
    }
}

/**
 * 10. Avatar — Coil image clipped to a circle; on null URL / load failure it falls back
 * to the first letter of [fallbackText] in `onPrimaryContainer` on a `primaryContainer`
 * circle. Never a gray silhouette.
 */
@Composable
fun Avatar(url: String?, fallbackText: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val letter = fallbackText.trim().firstOrNull { it.isLetterOrDigit() }
        ?.uppercaseChar()?.toString() ?: "?"
    val initials: @Composable () -> Unit = {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(cs.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter, color = cs.onPrimaryContainer, style = MaterialTheme.typography.titleMedium)
        }
    }
    if (url.isNullOrBlank()) {
        initials()
        return
    }
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier.size(size).clip(CircleShape),
        contentScale = ContentScale.Crop,
        loading = { initials() },
        error = { initials() },
    )
}

/**
 * 11. AppTextField — 56dp, `medium` shape, `surfaceContainerHigh` fill (dark) / `surface`
 * (light), `outline` border. `mono=true` renders input in [Mono] (the two code fields).
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    mono: Boolean = false,
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
    val container = if (isSystemInDarkTheme()) cs.surfaceContainerHigh else cs.surface
    val effectiveStyle = if (mono) textStyle.copy(fontFamily = Mono) else textStyle
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        isError = isError,
        enabled = enabled,
        textStyle = effectiveStyle,
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

/**
 * 12. TrustFooter — the ONE trust phrasing, verbatim everywhere: 14dp lock + `labelMedium
 * onSurfaceVariant` "Encrypted at rest · releases are end-to-end encrypted".
 */
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
            modifier = Modifier.size(14.dp),
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

/**
 * 13a. PrimaryButton — filled `primary`, 54dp, `large`, press-scale 0.98, inline spinner.
 * Max ONE per screen.
 */
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

/** 13b. SecondaryButton — 1dp `outline` border, 54dp. Quiet outlined action. */
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

// ─────────────────────────────────────────────────────────────────────────────
// Per-site tile art (kept — the 40dp tiles MediaRow leads with). SiteLogo now badges
// with the theme `warningBadge` role (the private `warningAmber` alias is retired).
// ─────────────────────────────────────────────────────────────────────────────

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

/** A site's real favicon/logo, resolved from the same favicon service the hub uses.
 *  Sized and rounded to match [SiteAvatar]; the letter-tile [SiteAvatar] is the
 *  loading/error fallback, so a favicon that never loads degrades to the nice avatar
 *  rather than a broken image. `warning=true` badges an unsupported (not-yet-mapped)
 *  site with the theme `warningBadge` role. */
@Composable
fun SiteLogo(domain: String, size: Int = 44, warning: Boolean = false) {
    if (!warning) {
        SiteFavicon(domain, size)
        return
    }
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
                .background(LocalExtendedColors.current.warningBadge),
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

/** The favicon itself (no badge): Google S2 then DuckDuckGo, falling back to the
 *  monogram avatar. Extracted so [SiteLogo] can overlay a warning badge. */
@Composable
private fun SiteFavicon(domain: String, size: Int) {
    val clean = domain.trim().removePrefix("www.")
    val sources = remember(clean) {
        val enc = Uri.encode(clean)
        listOf(
            "https://www.google.com/s2/favicons?domain=$enc&sz=64",
            "https://icons.duckduckgo.com/ip3/$enc.ico",
        )
    }
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
        loading = { SiteAvatar(domain, size) },
        error = { SiteAvatar(domain, size) },
        onError = { idx += 1 },
    )
}
