// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.online

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.fivehundred.AnimationSpeed
import io.github.rotundtapir.fivehundred.PacingGates
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.net.ClientMessage
import io.github.rotundtapir.fivehundred.net.ConfigureLobby
import io.github.rotundtapir.fivehundred.net.ConnectionState
import io.github.rotundtapir.fivehundred.net.CreateLobby
import io.github.rotundtapir.fivehundred.net.DisbandLobby
import io.github.rotundtapir.fivehundred.net.Emote
import io.github.rotundtapir.fivehundred.net.EmoteReceived
import io.github.rotundtapir.fivehundred.net.ErrorMessage
import io.github.rotundtapir.fivehundred.net.GameClient
import io.github.rotundtapir.fivehundred.net.GameOver
import io.github.rotundtapir.fivehundred.net.Hello
import io.github.rotundtapir.fivehundred.net.JoinLobby
import io.github.rotundtapir.fivehundred.net.KtorGameClient
import io.github.rotundtapir.fivehundred.net.LeaveLobby
import io.github.rotundtapir.fivehundred.net.LobbyDisbanded
import io.github.rotundtapir.fivehundred.net.LobbyState
import io.github.rotundtapir.fivehundred.net.OccupancyStatus
import io.github.rotundtapir.fivehundred.net.PROTOCOL_VERSION
import io.github.rotundtapir.fivehundred.net.PickSeat
import io.github.rotundtapir.fivehundred.net.Platform
import io.github.rotundtapir.fivehundred.net.RequestRematch
import io.github.rotundtapir.fivehundred.net.RoomPhase
import io.github.rotundtapir.fivehundred.net.SeatStatus
import io.github.rotundtapir.fivehundred.net.SendEmote
import io.github.rotundtapir.fivehundred.net.ServerMessage
import io.github.rotundtapir.fivehundred.net.SetName
import io.github.rotundtapir.fivehundred.net.SetReady
import io.github.rotundtapir.fivehundred.net.StartGame
import io.github.rotundtapir.fivehundred.net.SubmitAction
import io.github.rotundtapir.fivehundred.net.UpdateRequired
import io.github.rotundtapir.fivehundred.net.ViewUpdate
import io.github.rotundtapir.fivehundred.net.Welcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Which online screen is showing. Lobby/Game are chosen from the server's [LobbyState.phase]. */
enum class OnlineScreen { ENTRY, CREATE, JOIN, LOBBY, GAME }

/**
 * Client-side orchestration of an online match: owns the [GameClient], drives the connection (with
 * reconnect-and-resume on a dropped socket), maps server messages to UI state, and sends the
 * player's lobby/game actions. Rendering of the live game is delegated to [OnlineGameSession], which
 * applies the same pacing the local game uses.
 *
 * Requires an explicit `viewModel { OnlineViewModel(...) }` initializer — the reflective default
 * factory is JVM-only and throws on wasm.
 */
class OnlineViewModel(
    private val client: GameClient = KtorGameClient(),
) : ViewModel() {

    /** Kept in sync with the persisted setting by the host composable, exactly like GameViewModel. */
    val animationSpeed = MutableStateFlow(AnimationSpeed.NORMAL)
    val holdTricks = MutableStateFlow(false)

    private val pacing = PacingGates(animationSpeed, holdTricks)
    val session = OnlineGameSession(pacing, viewModelScope)

    private val _screen = MutableStateFlow(OnlineScreen.ENTRY)
    val screen: StateFlow<OnlineScreen> = _screen.asStateFlow()

    private val _lobby = MutableStateFlow<LobbyState?>(null)
    val lobby: StateFlow<LobbyState?> = _lobby.asStateFlow()

    val connection: StateFlow<ConnectionState> = client.state

    private val _seatNames = MutableStateFlow<Map<Seat, String>>(emptyMap())

    /** Display names for every seat (bot seats already carry "(bot)"), for the game screen labels. */
    val seatNames: StateFlow<Map<Seat, String>> = _seatNames.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _updateRequired = MutableStateFlow<String?>(null)
    val updateRequired: StateFlow<String?> = _updateRequired.asStateFlow()

    private val _gameOver = MutableStateFlow<GameOver?>(null)
    val gameOver: StateFlow<GameOver?> = _gameOver.asStateFlow()

    private val _emotes = MutableSharedFlow<EmoteReceived>(extraBufferCapacity = EMOTE_BUFFER)
    val emotes: SharedFlow<EmoteReceived> = _emotes.asSharedFlow()

    private var connectJob: Job? = null
    private var serverUrl: String = ""
    private var appVersion: String = ""
    private var platform: Platform = Platform.UNKNOWN
    private var sessionToken: String? = null
    private var pendingSnapshot = false
    private var intentionalDisconnect = false
    private var occupancy: Map<Seat, OccupancyStatus> = emptyMap()

    // True once the handshake (Hello → Welcome) has completed on the current socket. User actions
    // wait for this so a tap that races the connection isn't silently dropped, and so no client
    // message is ever sent before Hello.
    private val sessionReady = MutableStateFlow(false)

    // --- Pacing acknowledgements (forwarded from the game screen) ---------------------------------
    fun acknowledgeHandResult(handNumber: Int) = pacing.acknowledgeHandResult(handNumber)
    fun dealAnimationFinished(handNumber: Int) = pacing.dealAnimationFinished(handNumber)
    fun acknowledgeTrick(handNumber: Int, trickNumber: Int) = pacing.acknowledgeTrick(handNumber, trickNumber)

    /** Enter online mode: remember connection parameters and open the socket if not already open. */
    fun enter(serverUrl: String, appVersion: String, platform: Platform) {
        this.serverUrl = serverUrl
        this.appVersion = appVersion
        this.platform = platform
        _screen.value = OnlineScreen.ENTRY
        ensureConnected()
    }

    private fun ensureConnected() {
        if (connectJob?.isActive == true) return
        intentionalDisconnect = false
        session.start()
        // The configured serverUrl is a host address; the protocol's WebSocket lives at /ws.
        val wsUrl = serverUrl.trimEnd('/') + WS_PATH
        connectJob = viewModelScope.launch {
            var backoff = INITIAL_BACKOFF_MILLIS
            while (isActive && !intentionalDisconnect) {
                val hello = Hello(PROTOCOL_VERSION, appVersion, platform, sessionToken)
                val collector = launch { client.incoming.collect(::handleServerMessage) }
                val greeter = launch {
                    client.state.first { it == ConnectionState.CONNECTED }
                    client.send(hello)
                }
                runCatching { client.run(wsUrl) }
                collector.cancel()
                greeter.cancel()
                sessionReady.value = false
                if (intentionalDisconnect) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
            }
        }
    }

    // --- Navigation -------------------------------------------------------------------------------
    fun goToCreate() { _screen.value = OnlineScreen.CREATE }
    fun goToJoin() { _screen.value = OnlineScreen.JOIN }
    fun backToEntry() {
        _errorMessage.value = null
        _screen.value = OnlineScreen.ENTRY
    }

    fun dismissError() { _errorMessage.value = null }

    // --- Lobby actions ----------------------------------------------------------------------------
    fun createLobby(
        displayName: String,
        playerCount: Int,
        teamCount: Int,
        turnTimeoutSeconds: Int,
        idleDisbandMinutes: Int,
    ) = send(
        CreateLobby(
            displayName = displayName,
            playerCount = playerCount,
            teamCount = teamCount,
            turnTimeoutSeconds = turnTimeoutSeconds,
            idleDisbandMinutes = idleDisbandMinutes,
        ),
    )

    fun joinLobby(code: String, displayName: String) = send(JoinLobby(code.trim(), displayName))
    fun setName(displayName: String) = send(SetName(displayName))
    fun pickSeat(seat: Seat) = send(PickSeat(seat))
    fun setReady(ready: Boolean) = send(SetReady(ready))
    fun configure(turnTimeoutSeconds: Int?, idleDisbandMinutes: Int?) =
        send(ConfigureLobby(turnTimeoutSeconds, idleDisbandMinutes))
    fun startGame() = send(StartGame)
    fun requestRematch() = send(RequestRematch)
    fun sendEmote(emote: Emote) = send(SendEmote(emote))

    /** Leave the current lobby/game but stay connected (back to the online entry screen). */
    fun leaveLobby() {
        send(LeaveLobby)
        _lobby.value = null
        _gameOver.value = null
        session.reset()
        _screen.value = OnlineScreen.ENTRY
    }

    fun disbandLobby() = send(DisbandLobby)

    /** Fully exit online mode: leave any room, close the socket, and stop reconnecting. */
    fun exit() {
        runCatching { send(LeaveLobby) }
        intentionalDisconnect = true
        connectJob?.cancel()
        viewModelScope.launch { runCatching { client.close() } }
        _lobby.value = null
        _gameOver.value = null
        session.reset()
        _screen.value = OnlineScreen.ENTRY
    }

    // --- Game actions -----------------------------------------------------------------------------
    fun placeBid(bid: Bid) = submitAction(Action.PlaceBid(bid))
    fun discard(cards: List<Card>) = submitAction(Action.ExchangeKitty(cards))
    fun playCard(card: Card, nominate: Suit? = null) = submitAction(Action.PlayCard(card, nominate))

    private fun submitAction(action: Action) {
        val version = session.stateVersion.value ?: return
        send(SubmitAction(version, action))
    }

    private fun send(message: ClientMessage) {
        viewModelScope.launch {
            runCatching {
                // Wait for the handshake so a message never races an unopened socket (or precedes
                // Hello). Bounded so a dead connection surfaces rather than hanging the tap forever.
                withTimeout(SEND_WAIT_MILLIS) { sessionReady.first { it } }
                client.send(message)
            }
        }
    }

    // --- Incoming messages ------------------------------------------------------------------------
    private fun handleServerMessage(message: ServerMessage) {
        when (message) {
            is Welcome -> onWelcome(message)
            is UpdateRequired -> _updateRequired.value = message.message
            is LobbyState -> onLobbyState(message)
            is ViewUpdate -> {
                session.offer(message, snapshot = pendingSnapshot)
                pendingSnapshot = false
            }
            is SeatStatus -> onSeatStatus(message.seat, message.status)
            is GameOver -> _gameOver.value = message
            is EmoteReceived -> _emotes.tryEmit(message)
            is LobbyDisbanded -> onDisbanded(message)
            is ErrorMessage -> onError(message)
        }
    }

    private fun onWelcome(welcome: Welcome) {
        sessionToken = welcome.sessionToken
        pendingSnapshot = welcome.resumed != null
        sessionReady.value = true
    }

    private fun onLobbyState(state: LobbyState) {
        _lobby.value = state
        recomputeSeatNames(state)
        when (state.phase) {
            RoomPhase.PLAYING -> _screen.value = OnlineScreen.GAME
            RoomPhase.LOBBY, RoomPhase.FINISHED -> {
                if (_screen.value != OnlineScreen.LOBBY) session.reset()
                _screen.value = OnlineScreen.LOBBY
            }
        }
    }

    private fun onSeatStatus(seat: Seat, status: OccupancyStatus) {
        occupancy = occupancy + (seat to status)
        _lobby.value?.let(::recomputeSeatNames)
    }

    private fun recomputeSeatNames(state: LobbyState) {
        _seatNames.value = state.seats.associate { info ->
            val substituted = occupancy[info.seat] == OccupancyStatus.BOT_SUBSTITUTE
            val name = if (substituted && !info.name.endsWith(BOT_SUFFIX)) "${info.name} $BOT_SUFFIX" else info.name
            info.seat to name
        }
    }

    private fun onDisbanded(message: LobbyDisbanded) {
        _lobby.value = null
        _gameOver.value = null
        session.reset()
        occupancy = emptyMap()
        _errorMessage.value = "Lobby closed: ${message.reason.name.lowercase().replace('_', ' ')}"
        _screen.value = OnlineScreen.ENTRY
    }

    private fun onError(message: ErrorMessage) {
        _errorMessage.value = message.message.ifBlank { message.code.name }
    }

    override fun onCleared() {
        exit()
        super.onCleared()
    }

    private companion object {
        const val INITIAL_BACKOFF_MILLIS = 500L
        const val MAX_BACKOFF_MILLIS = 8000L
        const val EMOTE_BUFFER = 8
        const val BOT_SUFFIX = "(bot)"
        const val WS_PATH = "/ws"
        const val SEND_WAIT_MILLIS = 10_000L
    }
}
