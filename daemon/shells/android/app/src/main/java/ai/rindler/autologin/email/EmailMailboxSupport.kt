// EmailMailboxSupport — the PURE, side-effect-free pieces of the email-OTP lane, kept out
// of the Compose/keystore/network classes so each can be unit-tested with no Android
// runtime (see EmailMailboxSupportTest). Everything here is a plain function over plain
// data; nothing here touches storage, IMAP, or the UI.

package ai.rindler.autologin.email

/**
 * A linked mailbox: a DURABLE on-device secret. [host] is the bare IMAP host (the Go core
 * dials it on :993), [address] is both the IMAP username and the display address, and
 * [appPassword] is the mailbox app-password. [needsAttention] is device-local health, set
 * when a read fails authentication (a revoked/wrong app-password) so the UI can badge it.
 *
 * The app-password NEVER leaves the device: it is used only to dial IMAP locally (Go core),
 * and only the extracted code is ever relayed. It lives inside EncryptedSharedPreferences
 * exactly like a saved site password.
 */
data class EmailMailbox(
    val address: String,
    val host: String,
    val appPassword: String,
    val needsAttention: Boolean = false,
)

/** The canonical key for a mailbox: an email address compared case- and space-insensitively.
 *  Keying by this (never a single fixed slot) is what lets multiple mailboxes coexist. */
fun normalizeEmail(address: String): String = address.trim().lowercase()

/**
 * Strip ALL whitespace from a typed app-password. Google shows app-passwords grouped as
 * `xxxx xxxx xxxx xxxx` and users paste the spaces; IMAP LOGIN would then reject the padded
 * string and the mailbox would look broken for no reason. Applied on INPUT so what is stored
 * is exactly the 16 significant characters. (Providers never put a significant space in an
 * app-password, so this can never strip a real character.)
 */
fun stripAppPasswordWhitespace(raw: String): String = raw.filterNot { it.isWhitespace() }

/**
 * The bare IMAP host for an email address's domain, or null when unknown (the UI then asks
 * for a manual host). BARE on purpose: core/otp/imap.go dials `host:993` itself, so a
 * host carrying its own `:993` would become `host:993:993`. Covers the common consumer
 * providers that offer IMAP with an app-password.
 */
fun imapHostForDomain(email: String): String? {
    val domain = email.substringAfter("@", "").trim().lowercase()
    if (domain.isEmpty()) return null
    return when (domain) {
        "gmail.com", "googlemail.com" -> "imap.gmail.com"
        "outlook.com", "hotmail.com", "live.com", "msn.com" -> "outlook.office365.com"
        "icloud.com", "me.com", "mac.com" -> "imap.mail.me.com"
        "yahoo.com", "ymail.com", "rocketmail.com" -> "imap.mail.yahoo.com"
        "aol.com" -> "imap.aol.com"
        "fastmail.com", "fastmail.fm" -> "imap.fastmail.com"
        else -> null
    }
}

/** A provider-specific "where to make an app-password" hint: a label + the provider's own
 *  app-password page. Null when the provider is unknown (the generic help copy is shown). */
data class ProviderHelp(val label: String, val url: String)

/**
 * The app-password help for an address's domain, or null when unknown. These are the
 * providers' OWN pages — no app-password is ever a "read-only" grant (Gmail/iCloud/Yahoo
 * app-passwords grant full mailbox access, send included), so the copy around this must
 * never claim otherwise.
 */
fun providerAppPasswordHelp(email: String): ProviderHelp? {
    val domain = email.substringAfter("@", "").trim().lowercase()
    return when (domain) {
        "gmail.com", "googlemail.com" ->
            ProviderHelp("Create a Google app password", "https://myaccount.google.com/apppasswords")
        "icloud.com", "me.com", "mac.com" ->
            ProviderHelp("Create an Apple app-specific password", "https://account.apple.com/account/manage")
        "yahoo.com", "ymail.com", "rocketmail.com" ->
            ProviderHelp("Create a Yahoo app password", "https://login.yahoo.com/account/security/app-passwords")
        "outlook.com", "hotmail.com", "live.com", "msn.com" ->
            ProviderHelp("Create an Outlook app password", "https://account.live.com/proofs/AppPassword")
        "aol.com" ->
            ProviderHelp("Create an AOL app password", "https://login.aol.com/account/security/app-passwords")
        "fastmail.com", "fastmail.fm" ->
            ProviderHelp("Create a Fastmail app password", "https://app.fastmail.com/settings/security/apppasswords")
        else -> null
    }
}

/**
 * Order the linked mailboxes so the one whose address matches a saved login's [username]
 * is tried FIRST. This is an ordering HINT, never a filter: every mailbox stays in the
 * returned list (a site can email a code to a mailbox other than the one you sign in with),
 * only the likely one is polled first so the code is usually found on the first tick. A
 * null/blank username, or no match, returns the list unchanged. Stable: the relative order
 * of the non-matching mailboxes is preserved.
 */
fun orderMailboxesForLogin(mailboxes: List<EmailMailbox>, username: String?): List<EmailMailbox> {
    val key = username?.let { normalizeEmail(it) }.orEmpty()
    if (key.isEmpty()) return mailboxes
    val (match, rest) = mailboxes.partition { normalizeEmail(it.address) == key }
    return match + rest
}

/**
 * Whether a gomobile Exception message from Mobile.fetchEmailOTPOnce signals a broken
 * mailbox (a revoked/wrong app-password → ErrMailboxAuth), as opposed to a transient blip.
 * The Go sentinel's message contains this stable substring; a pinned Go test guards the
 * contract from both sides. A broken mailbox is badged + stops polling; everything else
 * keeps polling.
 */
fun isMailboxAuthError(message: String?): Boolean =
    message?.contains("rejected the app password", ignoreCase = true) == true

/**
 * A lightly masked address for a warning badge — enough to tell WHICH mailbox is broken
 * without spelling the whole address on the Home screen. Keeps the domain (so the user
 * recognises it) and the first character or two of the local part.
 */
fun maskEmail(address: String): String {
    val at = address.indexOf('@')
    if (at <= 0) return address
    val local = address.substring(0, at)
    val domain = address.substring(at) // includes '@'
    val head = local.take(2)
    return if (local.length <= 2) "$local$domain" else "$head…$domain"
}
