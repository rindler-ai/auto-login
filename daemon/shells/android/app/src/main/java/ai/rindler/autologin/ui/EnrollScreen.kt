package ai.rindler.autologin.ui

import ai.rindler.autologin.BuildConfig
import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.autologin.SupportedSite
import ai.rindler.autologin.fetchSupportedSites
import ai.rindler.autologin.requestSiteMapping
import ai.rindler.autologin.ui.theme.Dimens
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun EnrollScreen(store: KeystoreSecretSource, onDone: () -> Unit) {
    var site by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pwVisible by remember { mutableStateOf(false) }
    // Supported-site catalog, fetched live so the list auto-updates as new sites are
    // added. Empty after load == fetch failed (the catalog has ~1.7k entries).
    var loading by remember { mutableStateOf(true) }
    var catalog by remember { mutableStateOf<List<SupportedSite>>(emptyList()) }
    // Site-mapping-request state for the unsupported-site path.
    var requesting by remember { mutableStateOf(false) }
    var requestedFor by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    LaunchedEffect(Unit) {
        catalog = fetchSupportedSites(BuildConfig.CATALOG_URL)
        loading = false
    }

    val normalized = normalizeSite(site)
    val query = site.trim()
    // The exact supported domain the typed text resolves to (null = not supported).
    val matchedDomain = if (normalized.isEmpty()) null
    else catalog.firstOrNull { it.domain.equals(normalized, ignoreCase = true) }?.domain
    val isSupported = matchedDomain != null
    val matches = if (query.isEmpty()) emptyList()
    else catalog.asSequence()
        .filter { it.domain.contains(normalized, ignoreCase = true) || it.name.contains(query, ignoreCase = true) }
        .take(6)
        .toList()
    // Hide the dropdown once the field already holds a supported domain.
    val showSuggestions = !isSupported && matches.isNotEmpty()
    // Offer a "request site mapping" row (styled like the suggestions) when the typed
    // text looks like a real domain, isn't supported, and the catalog matched nothing.
    val looksLikeDomain = normalized.length >= 4 && normalized.contains(".")
    val showRequestMapping = !loading && !isSupported && matches.isEmpty() && looksLikeDomain
    val alreadyRequested = requestedFor != null && requestedFor == normalized

    val siteSupport = when {
        loading -> "Loading supported sites…"
        isSupported -> "Supported"
        showSuggestions -> "Pick a supported site below"
        showRequestMapping -> "Not supported yet — you can still save it below"
        query.isNotEmpty() -> "Keep typing the full site address"
        else -> "Start typing — e.g. instacart.com"
    }

    // Saving is allowed for ANY site, supported or not: an unsupported login is stored
    // with a warning badge (see HomeScreen) that resolves to the real favicon once
    // the hub maps the site. Only a site + username are required.
    val saveEnabled = query.isNotEmpty() && username.isNotBlank()

    AppScreen(title = "Add a login", onBack = onDone, footer = true) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Stored encrypted on this phone. When one of your sign-ins needs it, the credential is released automatically — end-to-end encrypted for that one login, only to a verified request.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            AppTextField(
                site,
                { site = it },
                "Website",
                supportingText = siteSupport,
            )
        }
        // Suggestions + mapping-request render in-flow inside THE one sanctioned box
        // (§1.6): surfaceContainerLow, 16dp radius. In-flow push-down stays.
        AnimatedVisibility(showSuggestions) {
            SuggestionBox {
                matches.forEachIndexed { i, item ->
                    MediaRow(
                        title = item.name,
                        compact = true,
                        leading = { SiteLogo(item.domain, size = 32) },
                        supporting = item.domain.takeIf { !item.name.equals(it, ignoreCase = true) },
                        onClick = { site = item.domain },
                    )
                    if (i != matches.lastIndex) InsetDivider(Dimens.keylineCompact)
                }
            }
        }
        AnimatedVisibility(showRequestMapping) {
            SuggestionBox {
                MediaRow(
                    title = if (alreadyRequested) "Requested — we'll add it soon" else "Request site mapping",
                    compact = true,
                    leading = {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (requesting) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.primary)
                            } else {
                                Icon(
                                    if (alreadyRequested) Icons.Rounded.Check else Icons.Rounded.Add,
                                    contentDescription = null,
                                    tint = cs.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                    supporting = normalized,
                    onClick = if (alreadyRequested || requesting) {
                        null
                    } else {
                        {
                            requesting = true
                            scope.launch {
                                val ok = requestSiteMapping(BuildConfig.CATALOG_URL, normalized)
                                requesting = false
                                if (ok) requestedFor = normalized
                            }
                        }
                    },
                )
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(16.dp))
            AppTextField(username, { username = it }, "Username or email")
            Spacer(Modifier.height(16.dp))
            AppTextField(
                password, { password = it }, "Password",
                visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { pwVisible = !pwVisible }) {
                        Icon(
                            if (pwVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (pwVisible) "Hide password" else "Show password",
                            tint = cs.onSurfaceVariant,
                        )
                    }
                },
            )
            Spacer(Modifier.height(32.dp))
            PrimaryButton(
                text = "Save to this device",
                enabled = saveEnabled,
                onClick = {
                    val json = JSONObject()
                        .put("username", username.trim())
                        .put("password", password)
                        .toString()
                    store.enroll(matchedDomain ?: site.trim(), json)
                    onDone()
                },
            )
        }
    }
}

/** The one sanctioned box (§1.6): the Enroll suggestions / mapping-request panel —
 *  `surfaceContainerLow`, 16dp radius, inset 16dp from the screen margins. */
@Composable
private fun SuggestionBox(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) { content() }
    }
}

/** Normalize a typed site to a bare domain for matching against the catalog:
 *  drop scheme, a leading www., and any path so "https://www.x.com/login" -> "x.com". */
private fun normalizeSite(s: String): String =
    s.trim().lowercase()
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .substringBefore("/")
        .trim()
