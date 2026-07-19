package ai.rindler.autologin.ui

import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertTrue
import org.testng.annotations.Test

/*
 * Testing strategy for gateEnroll(linkHub, storedHub, buildDefault, alreadyPaired):
 *   partition linkHub:       null (link named no hub),
 *                            == buildDefault exactly,
 *                            == buildDefault modulo case/whitespace,
 *                            any other server (the attacker shape)
 *   partition storedHub:     null, a self-hosted hub the user paired via Advanced
 *   partition alreadyPaired: false (first pair), true (a device token exists)
 * (A blank linkHub is not a legal input: parseEnrollUri maps blank to null.)
 *
 * The deep link is HOSTILE INPUT — any web page can fire autologin://paired with an
 * arbitrary hub — so the oracles are the two security properties, asserted across the
 * whole space in addition to per-cell representatives:
 *   1. the returned hub is ALWAYS a trusted value (the compiled-in default or the
 *      user's own stored hub), never the link's string;
 *   2. an already-paired device NEVER yields Proceed (silent re-enroll is forbidden).
 */
class EnrollGateTest {
    private val default = "wss://your-hub.example/v1/devices/connect"
    private val selfHosted = "wss://my-own-box.example/v1/devices/connect"
    private val attacker = "wss://attacker.example/v1/devices/connect"

    private val linkHubs = listOf(
        null,
        default,
        " WSS://YOUR-HUB.EXAMPLE/V1/DEVICES/CONNECT ",
        attacker,
    )
    private val storedHubs = listOf(null, selfHosted)
    private val pairedStates = listOf(false, true)

    /** covers linkHub == buildDefault, unpaired — the normal hosted sign-in. */
    @Test
    fun matchingHubOnAFreshDeviceProceeds() {
        val gate = gateEnroll(default, null, default, alreadyPaired = false)
        assertEquals(gate, EnrollGate.Proceed(default))
    }

    /**
     * covers linkHub == buildDefault modulo case/whitespace — tolerated, but the
     * pairing target is the CANONICAL compiled-in string, never the link's spelling.
     */
    @Test
    fun caseAndWhitespaceVariantOfTheDefaultProceedsCanonically() {
        val gate = gateEnroll(
            " WSS://YOUR-HUB.EXAMPLE/V1/DEVICES/CONNECT ", null, default, alreadyPaired = false,
        )
        assertEquals(gate, EnrollGate.Proceed(default))
    }

    /**
     * covers the attack: a web page fires autologin://paired?token=…&hub=wss://attacker…
     * to re-point the device at a server the attacker controls. The whole link must be
     * rejected — pairing there would hand over the relay, the device token, and (via the
     * unconditional AutoApprover) every stored credential.
     */
    @Test
    fun aHubNamingAnyOtherServerIsRejected() {
        val gate = gateEnroll(attacker, null, default, alreadyPaired = false)
        assertEquals(gate, EnrollGate.RejectedHub)
    }

    /** The reject verdict wins over the re-link confirmation: an untrusted server never
     *  even earns a dialog the user could mis-tap. */
    @Test
    fun anUntrustedHubIsRejectedEvenWhenAlreadyPaired() {
        val gate = gateEnroll(attacker, selfHosted, default, alreadyPaired = true)
        assertEquals(gate, EnrollGate.RejectedHub)
    }

    /** covers linkHub == null — falls back to the user's own stored hub, then the default. */
    @Test
    fun aLinkNamingNoHubFallsBackToStoredThenDefault() {
        assertEquals(
            gateEnroll(null, selfHosted, default, alreadyPaired = false),
            EnrollGate.Proceed(selfHosted),
        )
        assertEquals(
            gateEnroll(null, null, default, alreadyPaired = false),
            EnrollGate.Proceed(default),
        )
    }

    /**
     * covers alreadyPaired = true with an acceptable hub: a device that already holds a
     * token must get an explicit confirmation, not a silent re-enroll — a drive-by link
     * with a freshly minted (attacker-owned) token on the REAL hub would otherwise
     * quietly re-pair the phone into the attacker's account.
     */
    @Test
    fun anAlreadyPairedDeviceRequiresConfirmation() {
        assertEquals(
            gateEnroll(default, null, default, alreadyPaired = true),
            EnrollGate.ConfirmRelink(default),
        )
        assertEquals(
            gateEnroll(null, selfHosted, default, alreadyPaired = true),
            EnrollGate.ConfirmRelink(selfHosted),
        )
    }

    /**
     * Property 1 across the whole space: whatever the link says, the hub the app would
     * pair against is one the USER established (build default or stored) — the link's own
     * string never leaks through. This is the assertion that fails if anyone re-derives
     * the hub from the deep link again.
     */
    @Test
    fun thePairingTargetIsAlwaysATrustedHub() {
        for (linkHub in linkHubs) for (storedHub in storedHubs) for (paired in pairedStates) {
            val gate = gateEnroll(linkHub, storedHub, default, paired)
            val hub = when (gate) {
                is EnrollGate.Proceed -> gate.hub
                is EnrollGate.ConfirmRelink -> gate.hub
                is EnrollGate.RejectedHub -> continue
            }
            assertTrue(
                hub == default || hub == storedHub,
                "gate leaked an untrusted hub '$hub' (link=$linkHub stored=$storedHub paired=$paired)",
            )
        }
    }

    /** Property 2 across the whole space: a paired device never silently proceeds. */
    @Test
    fun aPairedDeviceNeverSilentlyReEnrolls() {
        for (linkHub in linkHubs) for (storedHub in storedHubs) {
            val gate = gateEnroll(linkHub, storedHub, default, alreadyPaired = true)
            assertFalse(
                gate is EnrollGate.Proceed,
                "already-paired device must confirm or reject, got $gate (link=$linkHub stored=$storedHub)",
            )
        }
    }

    /** hubHost feeds the confirmation copy: it must name the bare host, not a raw URL. */
    @Test
    fun hubHostExtractsTheBareHostForDialogCopy() {
        assertEquals(hubHost("wss://my-own-box.example/v1/devices/connect"), "my-own-box.example")
        assertEquals(hubHost("wss://my-own-box.example:8443/v1/devices/connect"), "my-own-box.example")
        assertEquals(hubHost("my-own-box.example"), "my-own-box.example")
    }
}
