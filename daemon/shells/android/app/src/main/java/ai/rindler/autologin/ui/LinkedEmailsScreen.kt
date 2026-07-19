// LinkedEmailsScreen — link one or more mailboxes for on-device email-OTP auto-read, and
// manage the ones already linked. The user enters their email + an IMAP app-password; the
// IMAP host is auto-detected from the address domain (a manual host field appears for
// anything unrecognised), and "Link" validates with a read-only IMAP login probe before
// storing the credential in the device keystore.
//
// NO OS permission is involved — the app-password IS the grant, and it NEVER transits the
// server (the Go core dials IMAP directly; only an extracted code is ever relayed). We do NOT
// claim the app-password is read-only: Gmail/iCloud/Yahoo app-passwords grant full mailbox
// access, send included, so the copy stays honest.
//
// Two modes, one screen:
//   onlyAdd = true  — the onboarding offer: JUST the add form for ONE address, no list, no
//     "add another". onDone returns to the caller (the setup checklist).
//   onlyAdd = false — the manage page (Settings / Home): the list of linked mailboxes, each
//     removable, broken ones badged with a "re-link to fix", plus an "Add an email" affordance.

package ai.rindler.autologin.ui

import ai.rindler.autologin.KeystoreSecretSource
import ai.rindler.autologin.RelayService
import ai.rindler.autologin.email.imapHostForDomain
import ai.rindler.autologin.email.isMailboxAuthError
import ai.rindler.autologin.email.maskEmail
import ai.rindler.autologin.email.providerAppPasswordHelp
import ai.rindler.autologin.email.stripAppPasswordWhitespace
import ai.rindler.mobile.Mobile
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LinkedEmailsScreen(
    store: KeystoreSecretSource,
    onBack: () -> Unit,
    onlyAdd: Boolean = false,
) {
    // Onboarding mode is the add form alone; after a link it returns to the caller so there
    // is never a list or an "add another" during setup.
    if (onlyAdd) {
        EmailAddForm(store = store, onLinked = onBack, onCancel = onBack)
        return
    }

    // Manage mode. `adding` overlays the add form on top of the list.
    var mailboxes by remember { mutableStateOf(store.linkedEmails()) }
    var adding by remember { mutableStateOf(mailboxes.isEmpty()) }
    var pendingRemove by remember { mutableStateOf<String?>(null) }
    var relinkPrefill by remember { mutableStateOf<String?>(null) }
    val cs = MaterialTheme.colorScheme

    if (adding) {
        EmailAddForm(
            store = store,
            prefillAddress = relinkPrefill,
            onLinked = { mailboxes = store.linkedEmails(); relinkPrefill = null; adding = false },
            onCancel = {
                relinkPrefill = null
                // Backing out of the add form with nothing linked leaves the screen entirely;
                // otherwise it drops back to the list.
                if (mailboxes.isEmpty()) onBack() else adding = false
            },
        )
        return
    }

    AppScreen(title = "Email sign-in codes", onBack = onBack, footer = true) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Auto-Login reads a sign-in code from these inboxes ON THIS DEVICE, only while " +
                    "a login is waiting for one. Your email password never leaves this phone and " +
                    "is never sent to our servers.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        }
        SectionHeader("LINKED MAILBOXES", trailing = if (mailboxes.isNotEmpty()) mailboxes.size.toString() else null)
        mailboxes.forEach { m ->
            MediaRow(
                title = m.address,
                leading = {
                    Icon(
                        if (m.needsAttention) Icons.Rounded.WarningAmber else Icons.Rounded.MailOutline,
                        contentDescription = null,
                        tint = if (m.needsAttention) {
                            ai.rindler.autologin.ui.theme.LocalExtendedColors.current.warning
                        } else {
                            cs.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp),
                    )
                },
                supporting = if (m.needsAttention) {
                    "Sign-in codes stopped working — tap to re-link"
                } else {
                    m.host
                },
                supportingColor = if (m.needsAttention) {
                    ai.rindler.autologin.ui.theme.LocalExtendedColors.current.warning
                } else {
                    null
                },
                // A broken mailbox taps to re-link (pre-filled); a healthy one has an explicit
                // remove button and no whole-row action.
                onClick = if (m.needsAttention) {
                    { relinkPrefill = m.address; adding = true }
                } else {
                    null
                },
                onClickLabel = if (m.needsAttention) "Re-link mailbox" else null,
                trailing = {
                    IconButton(onClick = { pendingRemove = m.address }) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "Remove ${m.address}",
                            tint = cs.onSurfaceVariant,
                        )
                    }
                },
            )
        }
        SettingRow(
            leading = Icons.Rounded.Add,
            title = "Add an email",
            onClick = { relinkPrefill = null; adding = true },
        )
        Spacer(Modifier.height(8.dp))
    }

    pendingRemove?.let { address ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            shape = MaterialTheme.shapes.large,
            title = { Text("Remove this mailbox?") },
            text = {
                Text(
                    "Auto-Login will stop reading sign-in codes from $address on this phone. " +
                        "The app-password is erased from this device — you can also delete it in " +
                        "your email provider's settings.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.unlinkEmail(address)
                    mailboxes = store.linkedEmails()
                    pendingRemove = null
                    if (mailboxes.isEmpty()) adding = true
                }) { Text("Remove", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("Cancel") } },
        )
    }
}

/** The add-a-mailbox form: email + app-password (+ manual host when the domain is unknown),
 *  validated by a read-only IMAP login probe before it is stored. [prefillAddress] seeds the
 *  email field when re-linking a broken mailbox. */
@Composable
private fun EmailAddForm(
    store: KeystoreSecretSource,
    onLinked: () -> Unit,
    onCancel: () -> Unit,
    prefillAddress: String? = null,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf(prefillAddress.orEmpty()) }
    var appPassword by remember { mutableStateOf("") }
    var pwVisible by remember { mutableStateOf(false) }
    var manualHost by remember { mutableStateOf("") }
    var linking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val detected = imapHostForDomain(email)
    val hostKnown = detected != null
    // core/otp/imap.go appends :993, so store a BARE host: strip any :port the user typed.
    val effectiveHost = (detected ?: manualHost.trim()).substringBefore(":").trim()
    val help = providerAppPasswordHelp(email)
    val canLink = email.contains("@") && appPassword.isNotEmpty() && effectiveHost.isNotBlank() && !linking

    AppScreen(title = "Link your email", onBack = onCancel, footer = true) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Auto-Login reads a sign-in code from your inbox ON THIS DEVICE, only while a " +
                    "login is waiting for one. Use an app-password (not your main password); it " +
                    "stays on this phone and is never sent to our servers.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            AppTextField(
                email, { email = it; error = null }, "Email address",
                supportingText = if (hostKnown) "Detected mailbox: $detected" else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            Spacer(Modifier.height(14.dp))
            AppTextField(
                appPassword,
                // Strip whitespace ON INPUT: providers show app-passwords as "xxxx xxxx xxxx
                // xxxx" and users paste the spaces, which IMAP LOGIN would then reject.
                { appPassword = stripAppPasswordWhitespace(it); error = null },
                "App password",
                visualTransformation = if (pwVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = { pwVisible = !pwVisible }) {
                        Icon(
                            if (pwVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (pwVisible) "Hide app password" else "Show app password",
                            tint = cs.onSurfaceVariant,
                        )
                    }
                },
            )
            if (!hostKnown && email.contains("@")) {
                Spacer(Modifier.height(14.dp))
                AppTextField(
                    manualHost, { manualHost = it; error = null }, "IMAP host (e.g. imap.example.com)",
                    supportingText = "We couldn't auto-detect your mailbox — enter its IMAP server",
                )
            }
            if (help != null) {
                Spacer(Modifier.height(12.dp))
                SecondaryButton(
                    text = help.label,
                    onClick = {
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(help.url)))
                        }
                    },
                    leading = {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                    },
                )
            }
            if (error != null) {
                Spacer(Modifier.height(4.dp))
                StatusLine(StatusKind.Error, error!!)
            }
            Spacer(Modifier.height(24.dp))
            PrimaryButton(
                text = "Link",
                enabled = canLink,
                loading = linking,
                onClick = {
                    linking = true
                    error = null
                    val host = effectiveHost
                    val addr = email.trim()
                    val pw = appPassword
                    scope.launch {
                        // Validate the credential with a LOGIN-ONLY probe before storing —
                        // verifyMailboxLogin authenticates and logs out, reading NO mail. (The
                        // earlier fetchEmailOTPOnce probe searched + fetched up to 20 message
                        // bodies to validate, which read the user's mail OUTSIDE any armed login
                        // window — contradicting this screen's own promise that mail is read only
                        // while a login is waiting.) It throws a typed error on a bad credential;
                        // the app-password never leaves the phone — the Go core dials IMAP directly.
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                Mobile.verifyMailboxLogin(host, addr, pw)
                            }
                        }
                        result.fold(
                            onSuccess = {
                                store.linkEmail(addr, host, pw)
                                RelayService.ensureRunning(ctx)
                                linking = false
                                onLinked()
                            },
                            onFailure = { e ->
                                error = if (isMailboxAuthError(e.message)) {
                                    "Couldn't sign in to that mailbox. Check the address and app-password."
                                } else {
                                    "Couldn't reach that mailbox. Check your connection and the IMAP host."
                                }
                                linking = false
                            },
                        )
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
