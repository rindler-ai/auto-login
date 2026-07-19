package ai.rindler.autologin.ui

import ai.rindler.autologin.ConnectionStatus
import ai.rindler.autologin.deriveConnectionStatus

/**
 * What the account header shows: the status line, the switch position, and whether a
 * toggle is still in flight.
 *
 * @property status what the user is TOLD about the relay.
 * @property switchOn where the switch sits.
 * @property inFlight whether the requested state has not landed yet.
 */
data class HeaderState(
    val status: ConnectionStatus,
    val switchOn: Boolean,
    val inFlight: Boolean,
)

/**
 * Derive the header from the relay's ACTUAL state and the state the user REQUESTED.
 *
 * The two disagree only while a toggle is in flight, because starting the relay
 * establishes its session asynchronously. The split matters: the switch answers the tap
 * immediately (so the control never feels dead) while the status line keeps reporting the
 * real session, which is the only thing the user can act on.
 *
 * @param running whether the relay session is actually established.
 * @param requested the state the user last asked for.
 * @return switchOn == requested; inFlight == (requested != running); status ==
 *   CONNECTING while in flight, else CONNECTED when running, else PAUSED.
 *
 * Invariant, and the reason this is a named function rather than an inline expression:
 * status is PAUSED only when the relay is genuinely down, and CONNECTED only when it is
 * genuinely up. It is never derived from `requested` alone. Deriving it from a stale copy
 * of the running flag is exactly the shipped bug where a freshly signed-in user saw
 * "Paused" while the relay was up and relaying.
 */
fun headerState(running: Boolean, requested: Boolean): HeaderState {
    val inFlight = requested != running
    return HeaderState(
        status = deriveConnectionStatus(running, toggleInFlight = inFlight),
        switchOn = requested,
        inFlight = inFlight,
    )
}

/**
 * The longest a relay toggle may sit "in flight" (requested != running) before the header
 * stops trusting it and snaps back to the truth. A real connect lands in well under a
 * second, so this is long enough that a healthy connect never trips it, yet short enough
 * that a toggle that can NEVER land unsticks in a few seconds instead of freezing forever.
 *
 * Without this bound, a toggle whose service never reports `running` — a device paired
 * before the serverPubkey change, where RelayService.ensureRunning early-returns, or a
 * Mobile.start that throws — leaves inFlight true forever: the status reads "Connecting…"
 * AND the switch is disabled (because in-flight), and the switch is the only recovery
 * control. A permanent dead end. (§4b)
 */
const val RELAY_INFLIGHT_TIMEOUT_MS: Long = 9_000L

/** How often the in-flight bound is re-checked while waiting out the timeout. */
const val RELAY_INFLIGHT_POLL_MS: Long = 250L

/**
 * Decide whether an in-flight relay toggle has waited long enough that the header must
 * abandon it and reconcile `requested` back to the actual `running` state. Pure, so the
 * timeout POLICY is unit-tested (HomeInFlightTimeoutTest) independently of the
 * LaunchedEffect in HomeScreen that measures the elapsed time and performs the reset.
 *
 * @param inFlight whether the requested state still disagrees with the actual state.
 * @param elapsedMs how long the toggle has been in flight.
 * @return true iff it is still in flight AND has reached [RELAY_INFLIGHT_TIMEOUT_MS]. A
 *   settled header (inFlight == false) never reconciles, whatever the elapsed time; the
 *   boundary is inclusive so the reset fires exactly at the timeout, not a poll later.
 */
fun shouldForceReconcile(inFlight: Boolean, elapsedMs: Long): Boolean =
    inFlight && elapsedMs >= RELAY_INFLIGHT_TIMEOUT_MS
