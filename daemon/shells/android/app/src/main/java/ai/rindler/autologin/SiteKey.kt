// SiteKey — the ONE canonical form a saved login is keyed by.
//
// The server's SecretPing carries a bare domain ("chase.com") and the Go core
// resolves it with an exact-match lookup(site) against this store. So the key a
// credential is SAVED under must be exactly the string a ping will ASK for:
// storing what the user typed ("www.chase.com", "https://chase.com/login") makes
// a row that looks saved on Home but can never serve a login. Every write path
// keys through normalizeSiteKey, and planSiteKeyMigration repairs rows written
// by older builds that stored the raw typed text.

package ai.rindler.autologin

private val TRAILING_PORT = Regex(":\\d+$")

/**
 * Normalize a typed site to the bare domain a ping is matched against:
 * trim + lowercase, drop a scheme, a leading `www.`, any path/query/fragment,
 * a port, and a trailing dot — so `" HTTPS://WWW.Chase.com:443/login/ "` ->
 * `"chase.com"`. Subdomains other than `www` are meaningful and kept
 * (`app.chase.com` stays `app.chase.com`). Input with no domain left after
 * stripping (blank, a bare scheme) normalizes to `""` — callers must treat an
 * empty result as "no usable key", never store under it.
 */
fun normalizeSiteKey(s: String): String =
    s.trim().lowercase()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore("/")
        .substringBefore("?")
        .substringBefore("#")
        .removePrefix("www.")
        .replace(TRAILING_PORT, "")
        .removeSuffix(".")
        .trim()

/**
 * A repair plan for a site index containing keys older builds stored raw:
 * [moves] re-key a row (oldKey -> normalizedKey); [drops] delete a raw row whose
 * normalized key ALREADY holds a row (the normalized row is the one that actually
 * serves pings, so it wins and the unreachable duplicate goes).
 */
data class SiteKeyMigration(
    val moves: List<Pair<String, String>>,
    val drops: List<String>,
)

/**
 * Plan the re-keying of stored site rows to normalized keys. Pure — the store
 * applies it. Rules, in order, per stored key:
 *  - already normalized: untouched (and it claims its key against later raws);
 *  - normalizes to "" (unintelligible): untouched — never strand a row under "";
 *  - normalized key already taken (by an original row or an earlier move): drop;
 *  - otherwise: move to the normalized key.
 */
fun planSiteKeyMigration(storedKeys: List<String>): SiteKeyMigration {
    val taken = storedKeys.filterTo(mutableSetOf()) { normalizeSiteKey(it) == it }
    val moves = mutableListOf<Pair<String, String>>()
    val drops = mutableListOf<String>()
    for (key in storedKeys) {
        val normalized = normalizeSiteKey(key)
        if (normalized == key || normalized.isEmpty()) continue
        if (normalized in taken) {
            drops.add(key)
        } else {
            moves.add(key to normalized)
            taken.add(normalized)
        }
    }
    return SiteKeyMigration(moves, drops)
}
