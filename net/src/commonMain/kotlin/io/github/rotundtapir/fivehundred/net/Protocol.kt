// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.net

import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.PlayerView
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire protocol for online 500. One WebSocket per client; every frame is a single JSON object
 * whose `"type"` discriminator selects a [ClientMessage] or [ServerMessage].
 *
 * Compatibility strategy is two-layered:
 *  - **Additive JSON evolution is the default.** New optional fields (with defaults) and new enum
 *    values never require a version bump — [WireJson] ignores unknown keys and coerces unknown enum
 *    values to each enum's `UNKNOWN` member.
 *  - **[PROTOCOL_VERSION] bumps only on a breaking change** (a field removal/retype, or a semantic
 *    change). The server advertises a supported range; a client outside it is told to update.
 */
const val PROTOCOL_VERSION: Int = 1

/** Which client build connected — reported in [Hello], used for cross-play diagnostics only. */
enum class Platform {
    @SerialName("android") ANDROID,
    @SerialName("web") WEB,
    @SerialName("unknown") UNKNOWN,
}

/** The fixed set of canned in-game messages. No free text ever crosses the wire (moderation-free). */
enum class Emote {
    @SerialName("wellPlayed") WELL_PLAYED,
    @SerialName("oops") OOPS,
    @SerialName("thinking") THINKING,
    @SerialName("niceHand") NICE_HAND,
    @SerialName("goodGame") GOOD_GAME,
    @SerialName("hurryUp") HURRY_UP,

    /** Forward-compatibility sink: an emote a newer peer sent that this build doesn't know. */
    @SerialName("unknown") UNKNOWN,
}

/** Machine-readable reason accompanying an [ErrorMessage]; the human string is advisory. */
enum class ErrorCode {
    @SerialName("badName") BAD_NAME,
    @SerialName("nameTaken") NAME_TAKEN,
    @SerialName("lobbyFull") LOBBY_FULL,
    @SerialName("noSuchLobby") NO_SUCH_LOBBY,
    @SerialName("seatTaken") SEAT_TAKEN,
    @SerialName("notCreator") NOT_CREATOR,
    @SerialName("notInLobby") NOT_IN_LOBBY,
    @SerialName("badConfig") BAD_CONFIG,
    @SerialName("wrongPhase") WRONG_PHASE,
    @SerialName("staleAction") STALE_ACTION,
    @SerialName("illegalAction") ILLEGAL_ACTION,
    @SerialName("rateLimited") RATE_LIMITED,
    @SerialName("malformed") MALFORMED,
    @SerialName("versionUnsupported") VERSION_UNSUPPORTED,
    @SerialName("serverDraining") SERVER_DRAINING,
    @SerialName("serverFull") SERVER_FULL,
    @SerialName("unknown") UNKNOWN,
}

/** Why a lobby ended for everyone. */
enum class DisbandReason {
    @SerialName("creatorDisbanded") CREATOR_DISBANDED,
    @SerialName("idleTimeout") IDLE_TIMEOUT,
    @SerialName("serverShutdown") SERVER_SHUTDOWN,
    @SerialName("unknown") UNKNOWN,
}

/** Who is currently playing a seat during a game. */
enum class OccupancyStatus {
    /** A connected human. */
    @SerialName("human") HUMAN,

    /** A human who dropped/idled; a bot is playing their cards until they reclaim the seat. */
    @SerialName("botSubstitute") BOT_SUBSTITUTE,

    /** A seat that was empty at start and is a bot for the whole game. */
    @SerialName("bot") BOT,

    @SerialName("unknown") UNKNOWN,
}

/** The room's lifecycle phase, mirrored to clients in [LobbyState]. */
enum class RoomPhase {
    @SerialName("lobby") LOBBY,
    @SerialName("playing") PLAYING,
    @SerialName("finished") FINISHED,
}

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

/** One seat's occupancy in a lobby snapshot. [name] already carries the "(bot)" suffix for bots. */
@Serializable
data class SeatInfo(
    val seat: Seat,
    val name: String,
    val isBot: Boolean,
    val ready: Boolean,
    val connected: Boolean,
)

/** Sent inside [Welcome] when a session token was honoured, so the client knows where it landed. */
@Serializable
data class ResumedState(val joinCode: String, val phase: RoomPhase)

/** Sane defaults, shared by client (lobby-creation UI) and server (validation floor/ceiling). */
const val DEFAULT_TURN_TIMEOUT_SECONDS: Int = 45
const val DEFAULT_IDLE_DISBAND_MINUTES: Int = 30
const val MIN_TURN_TIMEOUT_SECONDS: Int = 10
const val MAX_TURN_TIMEOUT_SECONDS: Int = 300
const val MIN_IDLE_DISBAND_MINUTES: Int = 1
const val MAX_IDLE_DISBAND_MINUTES: Int = 120

// ---------------------------------------------------------------------------------------------
// Client -> Server
// ---------------------------------------------------------------------------------------------

/** A frame sent by a client. */
@Serializable
sealed interface ClientMessage

/** Must be the first frame. A non-null [sessionToken] requests resuming a dropped session. */
@Serializable
@SerialName("hello")
data class Hello(
    val protocolVersion: Int,
    val appVersion: String,
    val platform: Platform = Platform.UNKNOWN,
    val sessionToken: String? = null,
) : ClientMessage

/** Create a new lobby and become its creator, taking a seat. [seed] is honoured only in dev mode. */
@Serializable
@SerialName("lobby.create")
data class CreateLobby(
    val displayName: String,
    val playerCount: Int,
    val teamCount: Int,
    val misereEnabled: Boolean = true,
    val noTrumpsEnabled: Boolean = true,
    val turnTimeoutSeconds: Int = DEFAULT_TURN_TIMEOUT_SECONDS,
    val idleDisbandMinutes: Int = DEFAULT_IDLE_DISBAND_MINUTES,
    val seed: Long? = null,
) : ClientMessage

/** Join an existing lobby by its 4-character code (case-insensitive). */
@Serializable
@SerialName("lobby.join")
data class JoinLobby(val code: String, val displayName: String) : ClientMessage

/** Change display name while in the lobby. */
@Serializable
@SerialName("lobby.setName")
data class SetName(val displayName: String) : ClientMessage

/** Claim/move to a free seat (implies its team = seat.index % teamCount). */
@Serializable
@SerialName("lobby.pickSeat")
data class PickSeat(val seat: Seat) : ClientMessage

/** Toggle this player's ready flag. */
@Serializable
@SerialName("lobby.ready")
data class SetReady(val ready: Boolean) : ClientMessage

/** Creator-only: adjust timeouts before the game starts. */
@Serializable
@SerialName("lobby.configure")
data class ConfigureLobby(
    val turnTimeoutSeconds: Int? = null,
    val idleDisbandMinutes: Int? = null,
) : ClientMessage

/** Creator-only: start the game; every empty seat is filled by a bot. */
@Serializable
@SerialName("lobby.start")
data object StartGame : ClientMessage

/** Leave the lobby/game. In a live game a bot takes over; the seat can be reclaimed by reconnecting. */
@Serializable
@SerialName("lobby.leave")
data object LeaveLobby : ClientMessage

/** Creator-only: end the lobby for everyone. */
@Serializable
@SerialName("lobby.disband")
data object DisbandLobby : ClientMessage

/** Creator-only, after a game: return the room to the lobby for another game. */
@Serializable
@SerialName("lobby.rematch")
data object RequestRematch : ClientMessage

/**
 * Submit a game action. [stateVersion] echoes the prompting [ViewUpdate]; a mismatch means the
 * action is stale (a double-tap or a race) and is rejected without disturbing the game — the network
 * analogue of [io.github.rotundtapir.cardkit.core.ChannelPlayer.trySubmit].
 */
@Serializable
@SerialName("game.action")
data class SubmitAction(val stateVersion: Int, val action: Action) : ClientMessage

/** Send a canned emote to the table. */
@Serializable
@SerialName("emote")
data class SendEmote(val emote: Emote) : ClientMessage

// ---------------------------------------------------------------------------------------------
// Server -> Client
// ---------------------------------------------------------------------------------------------

/** A frame sent by the server. */
@Serializable
sealed interface ServerMessage

/** Reply to [Hello] on success. [sessionToken] is stored by the client to enable reconnects. */
@Serializable
@SerialName("welcome")
data class Welcome(
    val sessionToken: String,
    val serverVersion: String,
    val resumed: ResumedState? = null,
) : ServerMessage

/** Reply to [Hello] when the client is too old; the server closes the socket after sending this. */
@Serializable
@SerialName("updateRequired")
data class UpdateRequired(val minAppVersion: String, val message: String) : ServerMessage

/** Full lobby snapshot, re-broadcast on every change (no deltas — the client never merges state). */
@Serializable
@SerialName("lobby.state")
data class LobbyState(
    val joinCode: String,
    val gameId: String,
    val config: LobbyConfig,
    val seats: List<SeatInfo>,
    val creatorSeat: Seat,
    val yourSeat: Seat? = null,
    val phase: RoomPhase,
) : ServerMessage

/**
 * A redacted per-seat view after every applied action (and on connect/reconnect). The view *is* the
 * turn prompt: [PlayerView.isMyTurn] plus its legal-action lists tell the client what to offer.
 * [turnRemainingMillis] (never an absolute timestamp — client clocks drift) drives the countdown.
 */
@Serializable
@SerialName("game.view")
data class ViewUpdate(
    val stateVersion: Int,
    val view: PlayerView,
    val turnRemainingMillis: Long? = null,
) : ServerMessage

/** Whose cards a seat is currently played by, so the UI can show "Alice (bot)" mid-game. */
@Serializable
@SerialName("game.seatStatus")
data class SeatStatus(val seat: Seat, val status: OccupancyStatus) : ServerMessage

/** Explicit room-phase transition to FINISHED, carrying the final scoreline. */
@Serializable
@SerialName("game.over")
data class GameOver(val winnerTeam: Int, val scores: Map<Int, Int>) : ServerMessage

/** A peer sent an emote. */
@Serializable
@SerialName("emote")
data class EmoteReceived(val seat: Seat, val emote: Emote = Emote.UNKNOWN) : ServerMessage

/** The lobby ended for everyone. */
@Serializable
@SerialName("lobby.disbanded")
data class LobbyDisbanded(val reason: DisbandReason = DisbandReason.UNKNOWN) : ServerMessage

/** A rejected request. [fatal] messages precede the server closing the socket. */
@Serializable
@SerialName("error")
data class ErrorMessage(
    val code: ErrorCode = ErrorCode.UNKNOWN,
    val message: String = "",
    val fatal: Boolean = false,
) : ServerMessage
