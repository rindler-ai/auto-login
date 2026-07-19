package ai.rindler.autologin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One site the hub currently supports, surfaced from the live public catalog. */
data class SupportedSite(val domain: String, val name: String)

/**
 * Fetch the live list of sites the service supports from the public catalog endpoint.
 *
 * The catalog is the same surfaced site_configs list that powers your hub,
 * so the enroll autocomplete auto-updates as new sites are added (fetched at runtime,
 * never hardcoded). Response shape:
 *   { "cards": [ { "domain": "...", "name": "...", "category": "..." }, ... ],
 *     "degraded": bool }
 *
 * Any failure (offline, non-2xx, malformed body) returns NULL — "could not load" —
 * which is a different fact from an empty list ("loaded, nothing in it"). Callers
 * must never treat null as "site not supported": during an outage nothing is known
 * about any site. The caller decides the fallback so a catalog outage never bricks
 * enrollment.
 */
suspend fun fetchSupportedSites(url: String): List<SupportedSite>? = withContext(Dispatchers.IO) {
    runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val cards = JSONObject(body).optJSONArray("cards") ?: return@runCatching null
            val out = ArrayList<SupportedSite>(cards.length())
            for (i in 0 until cards.length()) {
                val card = cards.optJSONObject(i) ?: continue
                val domain = card.optString("domain").trim()
                if (domain.isEmpty()) continue
                val name = card.optString("name").trim().ifEmpty { domain }
                out.add(SupportedSite(domain, name))
            }
            out
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}

/**
 * Ask the hub to map a site it doesn't support yet. POSTs the typed domain to the
 * public site-mapping-request endpoint (same host/origin as the catalog), which
 * pings the team's Slack. Best-effort: any failure returns false so the UI shows a
 * light error rather than crashing. The endpoint is derived from the catalog URL's
 * origin so dev and prod follow the same host as [fetchSupportedSites].
 */
suspend fun requestSiteMapping(catalogUrl: String, site: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val base = URL(catalogUrl)
        val port = if (base.port > 0) ":${base.port}" else ""
        val endpoint = URL("${base.protocol}://${base.host}$port/api/site-mapping-request")
        val conn = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val payload = JSONObject().put("site", site).put("platform", "android").toString()
            conn.outputStream.use { it.write(payload.toByteArray()) }
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)
}
