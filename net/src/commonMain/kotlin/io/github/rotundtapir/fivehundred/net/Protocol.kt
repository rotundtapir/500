// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.net

import io.github.rotundtapir.cardkit.net.CreateLobbyRequest
import io.github.rotundtapir.cardkit.net.DEFAULT_IDLE_DISBAND_MINUTES
import io.github.rotundtapir.cardkit.net.DEFAULT_TURN_TIMEOUT_SECONDS
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.PlayerView
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 500's own half of the wire protocol. Everything game-independent — the envelope, the lobby
 * messages, the wire enums, `Names`, the timeout bounds, the client — lives in `cardkit-net`; only
 * the parts that *are* 500 are here.
 *
 * Compatibility strategy is two-layered, and unchanged by that move:
 *  - **Additive JSON evolution is the default.** New optional fields (with defaults) and new enum
 *    values never require a version bump — [WireJson] ignores unknown keys and coerces unknown enum
 *    values to each enum's `UNKNOWN` member.
 *  - **[PROTOCOL_VERSION] bumps only on a breaking change** (a field removal/retype, or a semantic
 *    change). The server advertises a supported range; a client outside it is told to update.
 *
 * NOTE: adding a value to an enum embedded in [PlayerView] (its `Phase`/`Trump`/`Suit`/`Rank`, which
 * have no `UNKNOWN` sink) IS a breaking change for old clients — they would fail to decode the whole
 * [ViewUpdate] and silently stall. Such an addition must bump [PROTOCOL_VERSION]. The wire enums in
 * cardkit-net each carry an `UNKNOWN` member precisely so *they* can grow additively without a bump.
 */
const val PROTOCOL_VERSION: Int = 1

/** The negotiated rules for a lobby; echoed back so every client renders the same setup. */
@Serializable
data class LobbyConfig(
    val playerCount: Int,
    val teamCount: Int,
    val misereEnabled: Boolean = true,
    val noTrumpsEnabled: Boolean = true,
    val turnTimeoutSeconds: Int = DEFAULT_TURN_TIMEOUT_SECONDS,
    val idleDisbandMinutes: Int = DEFAULT_IDLE_DISBAND_MINUTES,
)

/**
 * Create a new lobby and become its creator, taking a seat. [seed] is honoured only in dev mode.
 *
 * Per-game, because its body *is* 500's setup: the table size, the team split, and the two house
 * rules. The server routes on the shared [CreateLobbyRequest] interface and converts this to a
 * [LobbyConfig] through 500's `GameDescriptor`.
 */
@Serializable
@SerialName("lobby.create")
data class CreateLobby(
    override val displayName: String,
    val playerCount: Int,
    val teamCount: Int,
    val misereEnabled: Boolean = true,
    val noTrumpsEnabled: Boolean = true,
    override val turnTimeoutSeconds: Int = DEFAULT_TURN_TIMEOUT_SECONDS,
    override val idleDisbandMinutes: Int = DEFAULT_IDLE_DISBAND_MINUTES,
    override val seed: Long? = null,
) : CreateLobbyRequest

/**
 * Submit a game action. `stateVersion` echoes the prompting [ViewUpdate]; a mismatch means the
 * action is stale (a double-tap or a race) and is rejected without disturbing the game — the network
 * analogue of [io.github.rotundtapir.cardkit.core.ChannelPlayer.trySubmit].
 */
typealias SubmitAction = io.github.rotundtapir.cardkit.net.SubmitAction<Action>

/**
 * A redacted per-seat view after every applied action (and on connect/reconnect). The view *is* the
 * turn prompt: [PlayerView.isMyTurn] plus its legal-action lists tell the client what to offer.
 * `turnRemainingMillis` (never an absolute timestamp — client clocks drift) drives the countdown.
 */
typealias ViewUpdate = io.github.rotundtapir.cardkit.net.ViewUpdate<PlayerView>

/** Full lobby snapshot, re-broadcast on every change (no deltas — the client never merges state). */
typealias LobbyState = io.github.rotundtapir.cardkit.net.LobbyState<LobbyConfig>

/**
 * The erased forms of the payload-carrying messages, for `is` checks: their type argument is gone at
 * runtime, so `is ViewUpdate` cannot compile while `is AnyViewUpdate` can. Narrow one with
 * [forFiveHundred] — safe because [WireJson] registers exactly one payload type per message, so any
 * update that decoded here carries 500's own.
 */
typealias AnyViewUpdate = io.github.rotundtapir.cardkit.net.ViewUpdate<*>

/** See [AnyViewUpdate]. */
typealias AnyLobbyState = io.github.rotundtapir.cardkit.net.LobbyState<*>

/** See [AnyViewUpdate]. */
typealias AnySubmitAction = io.github.rotundtapir.cardkit.net.SubmitAction<*>

@Suppress("UNCHECKED_CAST")
fun AnyViewUpdate.forFiveHundred(): ViewUpdate = this as ViewUpdate

@Suppress("UNCHECKED_CAST")
fun AnyLobbyState.forFiveHundred(): LobbyState = this as LobbyState
