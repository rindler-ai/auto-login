package ai.rindler.autologin.ui

import ai.rindler.autologin.BuildConfig
import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.autologin.SupportedSite
import ai.rindler.autologin.fetchSupportedSites
import ai.rindler.autologin.normalizeSiteKey
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
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun EnrollScreen(store: KeystoreSecretSource, onDone: () -> Unit) {
    // rememberSaveable for the non-secret fields so a rotation mid-entry keeps the
    // half-typed login form (§4d). The PASSWORD deliberately stays plain remember: a
    // password must never be written into the system-serialized state bundle, so it is
    // cleared on a recreate by design — the one field the user re-enters. `pwVisible`
    // stays remember too (it only gates showing a now-empty field).
    var site by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pwVisible by remember { mutableStateOf(false) }
    // Supported-site catalog, fetched live so the list auto-updates as new sites are
    // added. A FAILED fetch (null) is tracked separately from a loaded catalog: an
    // outage means we know NOTHING about any site, so the screen must never present
    // "not supported" — it says it couldn't check, and offers a retry.
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<List<SupportedSite>>(emptyList()) }
    var fetchAttempt by remember { mutableStateOf(0) }
    // Site-mapping-request state for the unsupported-site path.
    var requesting by remember { mutableStateOf(false) }
    var requestedFor by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    LaunchedEffect(fetchAttempt) {
        loading = true
        val fetched = fetchSupportedSites(BuildConfig.CATALOG_URL)
        loadFailed = fetched == null
        catalog = fetched ?: emptyList()
        loading = false
    }

    val normalized = normalizeSiteKey(site)
    val query = site.trim()
    // The exact supported domain the typed text resolves to (null = no match — which
    // means "not supported" ONLY when the catalog actually loaded).
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
    val showRequestMapping = canOfferMappingRequest(
        catalogLoaded = !loading && !loadFailed,
        isSupported = isSupported,
        hasMatches = matches.isNotEmpty(),
        looksLikeDomain = looksLikeDomain,
    )
    val alreadyRequested = requestedFor != null && requestedFor == normalized

    val siteSupport = when {
        loading -> "Checking supported sites…"
        loadFailed -> "Couldn't check supported sites — you can still save this login"
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
                "Stored encrypted on this phone. When one of your sign-ins needs it, your phone releases it automatically — end-to-end encrypted for that one login, only to a verified request.",
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
        // Catalog outage: say so honestly and offer a retry — never "not supported".
        AnimatedVisibility(!loading && loadFailed) {
            SuggestionBox {
                MediaRow(
                    title = "Couldn't load the list of supported sites",
                    compact = true,
                    leading = {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = cs.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    supporting = "Tap to try again",
                    onClick = { fetchAttempt++ },
                )
            }
        }
        AnimatedVisibility(showRequestMapping) {
            SuggestionBox {
                MediaRow(
                    title = if (alreadyRequested) "Requested — we'll work on adding it" else "Ask us to support this site",
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
            // Without an explicit Password keyboard the IME treats this as ordinary prose,
            // so predictive text can LEARN the user's site password and later suggest it.
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
            ),
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
                    // Key by the NORMALIZED domain — the exact string a server ping is
                    // matched against. The raw typed text ("www.chase.com",
                    // "https://chase.com/login") would store a row lookup() can never
                    // serve; that hit every save made while the catalog fetch was down.
                    // store.enroll() normalizes again (defense in depth).
                    store.enroll(matchedDomain ?: normalized.ifEmpty { site.trim() }, json)
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

/**
 * Whether to show the "Request site mapping" row — i.e. whether we may ASSERT the
 * typed site is not supported. Only a LOADED catalog can say that: a failed fetch
 * proves nothing about any site, so it must never present "not supported" (the
 * screen shows the couldn't-check + retry row instead). Pure; unit-tested in
 * CatalogGateTest.
 */
internal fun canOfferMappingRequest(
    catalogLoaded: Boolean,
    isSupported: Boolean,
    hasMatches: Boolean,
    looksLikeDomain: Boolean,
): Boolean = catalogLoaded && !isSupported && !hasMatches && looksLikeDomain
