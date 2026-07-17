package ai.rindler.autologin.ui

import ai.rindler.autologin.BuildConfig
import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.autologin.SupportedSite
import ai.rindler.autologin.fetchSupportedSites
import ai.rindler.autologin.requestSiteMapping
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
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

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        TopBar(title = "Add a login", onBack = onDone)
        Spacer(Modifier.height(8.dp))
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
        AnimatedVisibility(showSuggestions) {
            SuggestionList(matches) { picked -> site = picked }
        }
        AnimatedVisibility(showRequestMapping) {
            RequestMappingRow(
                domain = normalized,
                requested = alreadyRequested,
                requesting = requesting,
                onRequest = {
                    requesting = true
                    scope.launch {
                        val ok = requestSiteMapping(BuildConfig.CATALOG_URL, normalized)
                        requesting = false
                        if (ok) requestedFor = normalized
                    }
                },
            )
        }
        Spacer(Modifier.height(14.dp))
        AppTextField(username, { username = it }, "Username or email")
        Spacer(Modifier.height(14.dp))
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
        Spacer(Modifier.height(28.dp))
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
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.Lock, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Text(
                "Encrypted with this phone's secure hardware",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

/** Dropdown of supported-site matches beneath the Website field. Tapping a row sets
 *  the site to that exact supported domain. */
@Composable
private fun SuggestionList(items: List<SupportedSite>, onPick: (String) -> Unit) {
    Spacer(Modifier.height(8.dp))
    AppCard {
        Column {
            items.forEach { item ->
                SuggestionRow(item) { onPick(item.domain) }
            }
        }
    }
}

@Composable
private fun SuggestionRow(item: SupportedSite, onPick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SiteLogo(item.domain, size = 34)
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            // Only show the domain line when it adds info beyond the display name.
            if (!item.name.equals(item.domain, ignoreCase = true)) {
                Text(item.domain, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}

/** Dropdown-styled row (same shape as a suggestion) offering to request a mapping
 *  for an unsupported site. Tapping pings the team's Slack; once requested it shows a
 *  confirmed state. */
@Composable
private fun RequestMappingRow(
    domain: String,
    requested: Boolean,
    requesting: Boolean,
    onRequest: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(8.dp))
    AppCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !requested && !requesting, onClick = onRequest)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                if (requesting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.primary)
                } else {
                    Icon(
                        if (requested) Icons.Rounded.Check else Icons.Rounded.Add,
                        contentDescription = null,
                        tint = cs.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (requested) "Requested — we'll add it soon" else "Request site mapping",
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurface,
                )
                Text(domain, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
        }
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
