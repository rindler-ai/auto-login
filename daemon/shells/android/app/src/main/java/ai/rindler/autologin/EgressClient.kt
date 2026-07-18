// EgressClient — the device-authed POSTs that mint and revoke this device's egress
// tunnel token. When the user turns on "use my device as the hub's connection", the
// app asks the server for a per-user tunnel token so the paired device can run a
// tunnel egress (RelayService.reconcileEgress -> Mobile.startEgress) and the user's OWN
// agent sessions exit through THIS device's IP.
//
// Device-bearer authed (Authorization: Bearer <deviceToken from pairing>): the custody
// shell only ever holds a device token, so it uses the /devices/egress/* routes on the
// SAME hub server that minted the pairing token (smsRelayOrigin derives the https origin
// from the hub URL). Best-effort throughout: any failure returns null/false rather than
// throwing, so the toggle can never crash the settings screen.
//
// NEVER log the token or gateway — they authorize this user's egress and must stay on
// device (mirrors SmsRelayClient's CONSUME-AND-FORGET discipline for the OTP code).

package ai.rindler.autologin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The minted egress credential: a per-user tunnel token + the wss:// tunnel gateway. */
data class EgressMint(val token: String, val gateway: String)

/**
 * Mint an egress tunnel token: POST `/devices/egress/token` on the device-relay origin
 * derived from the hub URL, device-bearer authed, with body `{"name":name}`. On 2xx the
 * server returns `{"id","name","token","gateway"}`; this parses out `token` + `gateway`
 * into an [EgressMint] (null if either is missing/blank). Any non-2xx (401 unpaired, 503
 * the lane has no gateway, anything else) or transport/parse error returns null.
 * Best-effort: never throws. NEVER logs the token or gateway.
 */
suspend fun mintEgress(hubUrl: String, deviceToken: String, name: String): EgressMint? =
    withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = URL("${smsRelayOrigin(hubUrl)}/devices/egress/token")
            val conn = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $deviceToken")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            try {
                conn.outputStream.use { it.write(JSONObject().put("name", name).toString().toByteArray()) }
                when (conn.responseCode) {
                    in 200..299 -> {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        val token = json.optString("token").takeIf { it.isNotBlank() }
                        val gateway = json.optString("gateway").takeIf { it.isNotBlank() }
                        if (token != null && gateway != null) EgressMint(token, gateway) else null
                    }
                    else -> {
                        conn.errorStream?.close() // drain so the socket closes cleanly
                        null
                    }
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

/**
 * Revoke this device's egress server-side: POST `/devices/egress/disable` on the
 * device-relay origin, device-bearer authed, with an empty JSON body `{}`. Returns true
 * on 2xx (`{"disabled":true}`), false on any non-2xx or transport error. Best-effort:
 * never throws — the local unlink already stopped egress, so a failed revoke is a
 * server-side cleanup miss, not a user-facing failure.
 */
suspend fun disableEgress(hubUrl: String, deviceToken: String): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = URL("${smsRelayOrigin(hubUrl)}/devices/egress/disable")
            val conn = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $deviceToken")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            try {
                conn.outputStream.use { it.write(JSONObject().toString().toByteArray()) }
                when (conn.responseCode) {
                    in 200..299 -> true
                    else -> {
                        conn.errorStream?.close() // drain so the socket closes cleanly
                        false
                    }
                }
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }
