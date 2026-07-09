// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.cardkit.core.GameDriver
import io.github.rotundtapir.cardkit.core.Player
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Strategy
import io.github.rotundtapir.fivehundred.ai.FiveHundredBot
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.GameState
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.net.DisbandReason
import io.github.rotundtapir.fivehundred.net.Emote
import io.github.rotundtapir.fivehundred.net.EmoteReceived
import io.github.rotundtapir.fivehundred.net.ErrorCode
import io.github.rotundtapir.fivehundred.net.ErrorMessage
import io.github.rotundtapir.fivehundred.net.GameOver
import io.github.rotundtapir.fivehundred.net.LobbyConfig
import io.github.rotundtapir.fivehundred.net.LobbyDisbanded
import io.github.rotundtapir.fivehundred.net.LobbyState
import io.github.rotundtapir.fivehundred.net.MAX_IDLE_DISBAND_MINUTES
import io.github.rotundtapir.fivehundred.net.MAX_TURN_TIMEOUT_SECONDS
import io.github.rotundtapir.fivehundred.net.MIN_IDLE_DISBAND_MINUTES
import io.github.rotundtapir.fivehundred.net.MIN_TURN_TIMEOUT_SECONDS
import io.github.rotundtapir.fivehundred.net.Names
import io.github.rotundtapir.fivehundred.net.OccupancyStatus
import io.github.rotundtapir.fivehundred.net.ResumedState
import io.github.rotundtapir.fivehundred.net.RoomPhase
import io.github.rotundtapir.fivehundred.net.SeatInfo
import io.github.rotundtapir.fivehundred.net.SeatStatus
import io.github.rotundtapir.fivehundred.net.ServerMessage
import io.github.rotundtapir.fivehundred.net.ViewUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * One game room: a lobby that becomes a game and then a post-game screen. All state lives here and
 * is mutated only on the actor coroutine draining [commands]. The [GameDriver] runs in a child
 * coroutine and communicates back exclusively through [RoomCommand.StateProduced] /
 * [RoomCommand.GameFinished], keeping the single-writer invariant intact.
 */
class Room(
    val gameId: String,
    val joinCode: String,
    private val creatorToken: String,
    initialConfig: LobbyConfig,
    private val requestedSeed: Long?,
    private val scope: CoroutineScope,
    private val config: ServerConfig,
    private val sessionRegistry: SessionRegistry,
    private val metrics: Metrics,
    private val nowMillis: () -> Long,
    private val onClosed: (Room) -> Unit,
) {
    private val logger = LoggerFactory.getLogger("room")
    private val commands = Channel<RoomCommand>(Channel.UNLIMITED)
    private val bot: Strategy<PlayerView, Action> = FiveHundredBot()

    private var lobbyConfig = initialConfig
    private var phase = RoomPhase.LOBBY
    private val slots: List<Slot> = (0 until initialConfig.playerCount).map { Slot(Seat(it)) }

    private var rules: FiveHundredRules? = null
    private var latestState: GameState? = null
    private var stateVersion = 0
    private val latestViews = HashMap<Seat, ViewUpdate>()
    private var currentActor: Seat? = null
    private var currentTurnDeadline: Long? = null
    private var driverJob: Job? = null
    private var closed = false
    private var emptySince: Long? = null

    private val turnTimeoutMillis: Long
        get() = config.turnTimeoutMillisOverride ?: (lobbyConfig.turnTimeoutSeconds * 1000L)

    private val idleDisbandMillis: Long
        get() = config.idleDisbandMillisOverride ?: (lobbyConfig.idleDisbandMinutes * 60_000L)

    private class Slot(val seat: Seat) {
        var occupant: PlayerConnection? = null
        var name: String? = null
        var ready: Boolean = false
        var isBot: Boolean = false
        var host: SeatHost? = null
    }

    /** Enqueue a command for the actor loop. Never blocks (the channel is unbounded). */
    fun submit(command: RoomCommand) {
        commands.trySend(command)
    }

    /** Start the actor loop and the idle ticker. Called once, right after construction. */
    fun start() {
        scope.launch {
            for (command in commands) {
                runCatching { handle(command) }
                    .onFailure { logger.warn("room $joinCode command ${command::class.simpleName} failed", it) }
                if (closed) break
            }
        }
        scope.launch {
            val interval = (idleDisbandMillis / IDLE_CHECKS_PER_WINDOW).coerceIn(MIN_IDLE_INTERVAL, MAX_IDLE_INTERVAL)
            while (isActive && !closed) {
                delay(interval)
                submit(RoomCommand.IdleCheck)
            }
        }
    }

    /** True while at least one live game is running — used by drain/idle accounting. */
    fun isPlaying(): Boolean = phase == RoomPhase.PLAYING

    // A pure dispatch over the sealed command type — the branch count is the point, not accidental.
    @Suppress("CyclomaticComplexMethod")
    private suspend fun handle(command: RoomCommand) {
        when (command) {
            is RoomCommand.Join -> onJoin(command)
            is RoomCommand.Reconnect -> onReconnect(command)
            is RoomCommand.SetName -> onSetName(command)
            is RoomCommand.PickSeat -> onPickSeat(command)
            is RoomCommand.SetReady -> onSetReady(command)
            is RoomCommand.Configure -> onConfigure(command)
            is RoomCommand.Start -> onStart(command)
            is RoomCommand.Submit -> onSubmit(command)
            is RoomCommand.SendEmote -> onEmote(command)
            is RoomCommand.Leave -> onLeave(command)
            is RoomCommand.Disband -> onDisband(command)
            is RoomCommand.Rematch -> onRematch(command)
            is RoomCommand.Disconnected -> onDisconnected(command)
            is RoomCommand.StateProduced -> onStateProduced(command.state)
            is RoomCommand.GameFinished -> onGameFinished(command.state)
            is RoomCommand.SeatTimedOut -> onSeatTimedOut(command.seat)
            is RoomCommand.ForceDisband -> disband(command.reason)
            RoomCommand.IdleCheck -> onIdleCheck()
        }
    }

    // --- Lobby ------------------------------------------------------------------------------------

    private fun onJoin(cmd: RoomCommand.Join) {
        if (phase != RoomPhase.LOBBY) {
            reject(cmd.connection, ErrorCode.WRONG_PHASE, "Game already started")
            return
        }
        val validated = validateName(cmd.connection, cmd.displayName) ?: return
        val free = slots.firstOrNull { it.occupant == null && !it.isBot }
        if (free == null) {
            reject(cmd.connection, ErrorCode.LOBBY_FULL, "No free seats")
            return
        }
        seat(cmd.connection, free, validated)
        broadcastLobby()
        markActivity()
    }

    private fun onSetName(cmd: RoomCommand.SetName) {
        val slot = slotOf(cmd.connection) ?: return rejectNotInLobby(cmd.connection)
        if (phase != RoomPhase.LOBBY) return reject(cmd.connection, ErrorCode.WRONG_PHASE, "Cannot rename mid-game")
        val validated = validateName(cmd.connection, cmd.displayName) ?: return
        slot.name = validated
        broadcastLobby()
    }

    @Suppress("ReturnCount") // guard-clause validation reads clearer than nesting
    private fun onPickSeat(cmd: RoomCommand.PickSeat) {
        if (phase != RoomPhase.LOBBY) return reject(cmd.connection, ErrorCode.WRONG_PHASE, "Cannot move mid-game")
        val from = slotOf(cmd.connection) ?: return rejectNotInLobby(cmd.connection)
        val target = slots.getOrNull(cmd.seat.index)
            ?: return reject(cmd.connection, ErrorCode.BAD_CONFIG, "No such seat")
        if (target === from) return
        if (target.occupant != null || target.isBot) {
            return reject(cmd.connection, ErrorCode.SEAT_TAKEN, "Seat taken")
        }
        val name = from.name
        clearSlot(from)
        seat(cmd.connection, target, name)
        broadcastLobby()
    }

    private fun onSetReady(cmd: RoomCommand.SetReady) {
        val slot = slotOf(cmd.connection) ?: return rejectNotInLobby(cmd.connection)
        slot.ready = cmd.ready
        broadcastLobby()
        markActivity()
    }

    private fun onConfigure(cmd: RoomCommand.Configure) {
        if (!isCreator(cmd.connection)) return reject(cmd.connection, ErrorCode.NOT_CREATOR, "Only the creator")
        if (phase != RoomPhase.LOBBY) return reject(cmd.connection, ErrorCode.WRONG_PHASE, "Lobby only")
        lobbyConfig = lobbyConfig.copy(
            turnTimeoutSeconds = cmd.turnTimeoutSeconds?.coerceIn(MIN_TURN_TIMEOUT_SECONDS, MAX_TURN_TIMEOUT_SECONDS)
                ?: lobbyConfig.turnTimeoutSeconds,
            idleDisbandMinutes = cmd.idleDisbandMinutes?.coerceIn(MIN_IDLE_DISBAND_MINUTES, MAX_IDLE_DISBAND_MINUTES)
                ?: lobbyConfig.idleDisbandMinutes,
        )
        broadcastLobby()
    }

    // --- Game start & play ------------------------------------------------------------------------

    private fun onStart(cmd: RoomCommand.Start) {
        if (!isCreator(cmd.connection)) return reject(cmd.connection, ErrorCode.NOT_CREATOR, "Only the creator")
        if (phase != RoomPhase.LOBBY) return reject(cmd.connection, ErrorCode.WRONG_PHASE, "Already started")
        val presentHumans = slots.filter { it.occupant != null }
        if (presentHumans.isEmpty() || presentHumans.any { !it.ready }) {
            return reject(cmd.connection, ErrorCode.BAD_CONFIG, "All players must be ready")
        }
        launchGame()
    }

    private fun launchGame() {
        val gameRules = FiveHundredRules(
            playerCount = lobbyConfig.playerCount,
            teamCount = lobbyConfig.teamCount,
            misereEnabled = lobbyConfig.misereEnabled,
            noTrumpsEnabled = lobbyConfig.noTrumpsEnabled,
        )
        rules = gameRules
        val seed = (if (config.devMode) requestedSeed else null) ?: Random.nextLong()
        val botNames = pickBotNames(slots.count { it.occupant == null })
        var botIndex = 0
        val players = HashMap<Seat, Player<PlayerView, Action>>()
        for (slot in slots) {
            val host = SeatHost(
                seat = slot.seat,
                bot = bot,
                botRandom = Random(seed + slot.seat.index + 1),
                turnTimeout = turnTimeoutMillis.milliseconds,
                onTimedOut = { seat -> submit(RoomCommand.SeatTimedOut(seat)) },
            )
            if (slot.occupant == null) {
                slot.isBot = true
                slot.name = Names.botLabel(botNames[botIndex++])
                host.permanentBot = true
            } else {
                host.occupant = slot.occupant
            }
            slot.host = host
            players[slot.seat] = host
        }
        phase = RoomPhase.PLAYING
        broadcastLobby()
        metrics.gameStarted()
        val initial = gameRules.newGame(seed)
        driverJob = scope.launch {
            val terminal = GameDriver(gameRules, players).play(initial) { state ->
                submit(RoomCommand.StateProduced(state))
            }
            submit(RoomCommand.GameFinished(terminal))
        }
    }

    private fun onStateProduced(state: GameState) {
        val gameRules = rules ?: return
        latestState = state
        stateVersion++
        val terminal = gameRules.isTerminal(state)
        currentActor = if (terminal) null else gameRules.currentActor(state)
        currentTurnDeadline = if (currentActor != null) nowMillis() + turnTimeoutMillis else null
        for (slot in slots) {
            val view = gameRules.view(state, slot.seat)
            val remaining = if (slot.seat == currentActor) turnTimeoutMillis else null
            val update = ViewUpdate(stateVersion, view, remaining)
            latestViews[slot.seat] = update
            slot.occupant?.let { deliver(it, update) }
        }
    }

    @Suppress("ReturnCount") // guard-clause validation reads clearer than nesting
    private fun onSubmit(cmd: RoomCommand.Submit) {
        if (phase != RoomPhase.PLAYING) return reject(cmd.connection, ErrorCode.WRONG_PHASE, "No game in progress")
        val slot = slotOf(cmd.connection) ?: return rejectNotInLobby(cmd.connection)
        val gameRules = rules ?: return
        val state = latestState ?: return
        if (cmd.stateVersion != stateVersion || currentActor != slot.seat) {
            return reject(cmd.connection, ErrorCode.STALE_ACTION, "Not your turn / stale", fatal = false)
        }
        // Validate by trial-applying: apply is pure, the state can't change while the driver is
        // blocked in decide(), and this is the only safe guard — the driver's real apply() throws on
        // an illegal action and would kill the room coroutine.
        val illegal = runCatching { gameRules.apply(state, slot.seat, cmd.action) }.isFailure
        if (illegal) {
            metrics.rejected(ErrorCode.ILLEGAL_ACTION)
            return reject(cmd.connection, ErrorCode.ILLEGAL_ACTION, "Illegal action", fatal = false)
        }
        slot.host?.submit(cmd.action)
        markActivity()
    }

    private fun onSeatTimedOut(seat: Seat) {
        val slot = slots.getOrNull(seat.index) ?: return
        slot.occupant = null
        slot.host?.occupant = null
        broadcastSeatStatus(seat, OccupancyStatus.BOT_SUBSTITUTE)
    }

    private fun onGameFinished(state: GameState) {
        phase = RoomPhase.FINISHED
        driverJob = null
        metrics.gameCompleted()
        val winner = state.winner ?: -1
        broadcastAll(GameOver(winner, state.scores))
        broadcastLobby()
    }

    // --- Emotes, leaving, rematch, disband --------------------------------------------------------

    private fun onEmote(cmd: RoomCommand.SendEmote) {
        val slot = slotOf(cmd.connection) ?: return rejectNotInLobby(cmd.connection)
        if (cmd.emote == Emote.UNKNOWN) return
        broadcastAll(EmoteReceived(slot.seat, cmd.emote))
    }

    private fun onLeave(cmd: RoomCommand.Leave) {
        val slot = slotOf(cmd.connection) ?: return
        if (phase == RoomPhase.PLAYING) {
            slot.occupant = null
            slot.host?.occupant = null
            broadcastSeatStatus(slot.seat, OccupancyStatus.BOT_SUBSTITUTE)
        } else if (isCreator(cmd.connection)) {
            disband(DisbandReason.CREATOR_DISBANDED)
            return
        } else {
            clearSlot(slot)
            broadcastLobby()
        }
        detachConnection(cmd.connection)
        recomputeEmptiness()
    }

    private fun onDisband(cmd: RoomCommand.Disband) {
        if (!isCreator(cmd.connection)) return reject(cmd.connection, ErrorCode.NOT_CREATOR, "Only the creator")
        disband(DisbandReason.CREATOR_DISBANDED)
    }

    private fun onRematch(cmd: RoomCommand.Rematch) {
        if (!isCreator(cmd.connection)) return reject(cmd.connection, ErrorCode.NOT_CREATOR, "Only the creator")
        if (phase != RoomPhase.FINISHED) return reject(cmd.connection, ErrorCode.WRONG_PHASE, "Game not finished")
        // Keep humans in their seats; drop bot-only seats back to open; clear ready + game state.
        for (slot in slots) {
            slot.ready = false
            slot.host = null
            if (slot.isBot) {
                slot.isBot = false
                slot.name = null
                slot.occupant = null
            }
        }
        rules = null
        latestState = null
        latestViews.clear()
        currentActor = null
        currentTurnDeadline = null
        phase = RoomPhase.LOBBY
        broadcastLobby()
    }

    // --- Connection lifecycle ---------------------------------------------------------------------

    private fun onReconnect(cmd: RoomCommand.Reconnect) {
        val slot = slots.getOrNull(cmd.seat.index) ?: return
        val zombie = slot.occupant
        if (zombie != null && zombie.id != cmd.connection.id) zombie.requestClose()
        slot.occupant = cmd.connection
        slot.host?.occupant = cmd.connection
        cmd.connection.roomId = gameId
        cmd.connection.seat = cmd.seat
        sessionRegistry.bind(cmd.connection.sessionToken, gameId, cmd.seat)
        deliver(cmd.connection, lobbyStateFor(cmd.connection))
        replayView(cmd.connection, cmd.seat)
        if (phase == RoomPhase.PLAYING) broadcastSeatStatus(cmd.seat, OccupancyStatus.HUMAN)
        recomputeEmptiness()
    }

    private fun onDisconnected(cmd: RoomCommand.Disconnected) {
        val slot = slotOf(cmd.connection) ?: return
        if (phase == RoomPhase.PLAYING) {
            slot.occupant = null
            slot.host?.occupant = null
            broadcastSeatStatus(slot.seat, OccupancyStatus.BOT_SUBSTITUTE)
        } else {
            clearSlot(slot)
            if (isCreator(cmd.connection) && phase == RoomPhase.LOBBY) {
                disband(DisbandReason.CREATOR_DISBANDED)
                return
            }
            broadcastLobby()
        }
        recomputeEmptiness()
    }

    private fun onIdleCheck() {
        val since = emptySince ?: return
        if (nowMillis() - since >= idleDisbandMillis) disband(DisbandReason.IDLE_TIMEOUT)
    }

    // --- Helpers ----------------------------------------------------------------------------------

    private fun seat(connection: PlayerConnection, slot: Slot, name: String?) {
        slot.occupant = connection
        slot.name = name
        slot.ready = false
        connection.roomId = gameId
        connection.seat = slot.seat
        sessionRegistry.bind(connection.sessionToken, gameId, slot.seat)
    }

    private fun clearSlot(slot: Slot) {
        slot.occupant?.let { detachConnection(it) }
        slot.occupant = null
        slot.name = null
        slot.ready = false
    }

    private fun detachConnection(connection: PlayerConnection) {
        connection.roomId = null
        connection.seat = null
    }

    private fun slotOf(connection: PlayerConnection): Slot? =
        slots.firstOrNull { it.occupant?.id == connection.id }

    private fun isCreator(connection: PlayerConnection): Boolean =
        connection.sessionToken == creatorToken

    private fun validateName(connection: PlayerConnection, raw: String): String? {
        return when (val result = Names.validate(raw)) {
            is Names.Result.Ok -> result.name
            is Names.Result.Rejected -> {
                metrics.rejected(ErrorCode.BAD_NAME)
                reject(connection, ErrorCode.BAD_NAME, "Name not allowed: ${result.reason}")
                null
            }
        }
    }

    private fun creatorSeat(): Seat =
        slots.firstOrNull { it.occupant?.sessionToken == creatorToken }?.seat ?: Seat(0)

    private fun lobbyStateFor(connection: PlayerConnection): LobbyState = LobbyState(
        joinCode = joinCode,
        gameId = gameId,
        config = lobbyConfig,
        seats = slots.map {
            SeatInfo(
                seat = it.seat,
                name = it.name ?: "",
                isBot = it.isBot,
                ready = it.ready,
                connected = it.occupant?.connected == true,
            )
        },
        creatorSeat = creatorSeat(),
        yourSeat = slotOf(connection)?.seat,
        phase = phase,
    )

    private fun broadcastLobby() {
        for (slot in slots) {
            slot.occupant?.let { deliver(it, lobbyStateFor(it)) }
        }
    }

    private fun broadcastSeatStatus(seat: Seat, status: OccupancyStatus) =
        broadcastAll(SeatStatus(seat, status))

    private fun broadcastAll(message: ServerMessage) {
        for (slot in slots) slot.occupant?.let { deliver(it, message) }
    }

    private fun replayView(connection: PlayerConnection, seat: Seat) {
        val update = latestViews[seat] ?: return
        val deadline = currentTurnDeadline
        val remaining = if (currentActor == seat && deadline != null) {
            (deadline - nowMillis()).coerceAtLeast(0)
        } else {
            null
        }
        deliver(connection, update.copy(turnRemainingMillis = remaining))
    }

    private fun deliver(connection: PlayerConnection, message: ServerMessage) {
        if (!connection.enqueue(message)) connection.requestClose()
    }

    private fun reject(
        connection: PlayerConnection,
        code: ErrorCode,
        message: String,
        fatal: Boolean = false,
    ) {
        metrics.rejected(code)
        deliver(connection, ErrorMessage(code, message, fatal))
    }

    private fun rejectNotInLobby(connection: PlayerConnection) =
        reject(connection, ErrorCode.NOT_IN_LOBBY, "You are not seated in this room")

    private fun markActivity() {
        emptySince = null
    }

    private fun recomputeEmptiness() {
        val anyConnected = slots.any { it.occupant?.connected == true }
        emptySince = if (anyConnected) null else (emptySince ?: nowMillis())
    }

    private fun disband(reason: DisbandReason) {
        if (closed) return
        broadcastAll(LobbyDisbanded(reason))
        for (slot in slots) slot.occupant?.let { detachConnection(it) }
        close()
    }

    private fun close() {
        closed = true
        driverJob?.cancel()
        commands.close()
        onClosed(this)
    }

    /** Broadcast a shutdown notice and detach everyone. Called on graceful server SIGTERM. */
    fun shutdown() {
        submit(RoomCommand.ForceDisband(DisbandReason.SERVER_SHUTDOWN))
    }

    /** Build the [ResumedState] for a client that reconnected into this room. */
    fun resumedState(): ResumedState = ResumedState(joinCode, phase)

    private fun pickBotNames(count: Int): List<String> =
        BOT_NAMES.shuffled(Random(joinCode.hashCode().toLong())).take(count.coerceAtLeast(0))

    private companion object {
        const val IDLE_CHECKS_PER_WINDOW = 6L
        const val MIN_IDLE_INTERVAL = 200L
        const val MAX_IDLE_INTERVAL = 60_000L

        val BOT_NAMES = listOf(
            "Alice", "Bruce", "Cleo", "Dev", "Esther", "Frank", "Greta", "Hugo",
            "Ivy", "Jack", "Kira", "Leo", "Mona", "Nate", "Opal", "Wally",
        )
    }
}
