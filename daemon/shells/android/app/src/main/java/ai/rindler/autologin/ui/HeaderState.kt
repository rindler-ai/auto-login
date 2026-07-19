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
