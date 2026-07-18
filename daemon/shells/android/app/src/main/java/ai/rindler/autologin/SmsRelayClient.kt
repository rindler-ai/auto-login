// SmsRelayClient — the device-authed POST that hands a 2FA code to the server's
// SMS-relay rendezvous, so a paused login can finish.
//
// Two callers converge here on the EXACT same endpoint and rendezvous:
//   - the manual-entry screen (ManualCodeScreen): the user types the code, and
//   - the SMS auto-read path (sms/OtpDelivery), fed by an incoming text the user
//     opted into (RECEIVE_SMS): it extracts the code ON DEVICE first, so only the
//     code — never the message body — is ever sent.
// From the paused login's perspective there is no difference between the two.
//
// Device-bearer authed (Authorization: Bearer <deviceToken from pairing>): the
// custody shell holds no web session, only a device token, so it uses the
// /devices/sms-relay/* routes, exactly as the iOS shell does (ManualCodeView.swift).
// CONSUME-AND-FORGET: the code is never logged here.

package ai.rindler.autologin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Outcome of handing a code to the rendezvous. */
enum class CodeSubmitResult {
    /** The code reached a login that was waiting for it. */
    DELIVERED,

    /** No login is currently waiting — a harmless drop (e.g. a stray/old text). */
    NO_PENDING_LOGIN,

    /** The device's pairing was rejected (401) — re-pair needed. */
    UNAUTHORIZED,

    /** Network/transport/parse failure. */
    FAILED,
}

/**
 * Derive the https origin the device-authed SMS-relay routes live on from HUB_URL
 * (`wss://host[:port]/v1/devices/connect` -> `https://host[:port]`). It is the SAME
 * hub server that minted the pairing token, so the device bearer is valid there.
 */
fun smsRelayOrigin(hubUrl: String): String {
    val https = hubUrl.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")
    val u = URL(https)
    val port = if (u.port > 0) ":${u.port}" else ""
    return "${u.protocol}://${u.host}$port"
}

/**
 * POST a 2FA code to `POST /devices/sms-relay/manual`. The server routes it into the
 * DeliverOTP rendezvous a paused login blocks on (up to ~90s). Best-effort: any
 * failure returns [CodeSubmitResult.FAILED] rather than throwing, so neither the
 * manual screen nor the background receiver can crash on a bad network.
 */
suspend fun submitOtpCode(hubUrl: String, deviceToken: String, code: String): CodeSubmitResult =
    withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = URL("${smsRelayOrigin(hubUrl)}/devices/sms-relay/manual")
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
                conn.outputStream.use { it.write(JSONObject().put("code", code).toString().toByteArray()) }
                when (conn.responseCode) {
                    in 200..299 -> {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        when (JSONObject(body).optString("status")) {
                            "delivered" -> CodeSubmitResult.DELIVERED
                            "no_pending_login" -> CodeSubmitResult.NO_PENDING_LOGIN
                            else -> CodeSubmitResult.FAILED
                        }
                    }
                    401 -> CodeSubmitResult.UNAUTHORIZED
                    else -> {
                        conn.errorStream?.close() // drain so the socket closes cleanly
                        CodeSubmitResult.FAILED
                    }
                }
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(CodeSubmitResult.FAILED)
    }
