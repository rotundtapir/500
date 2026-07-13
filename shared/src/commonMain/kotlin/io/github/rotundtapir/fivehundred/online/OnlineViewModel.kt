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
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.net.ClientMessage
import io.github.rotundtapir.fivehundred.net.ConfigureLobby
import io.github.rotundtapir.fivehundred.net.ConnectionState
import io.github.rotundtapir.fivehundred.net.CreateLobby
import io.github.rotundtapir.fivehundred.net.DisbandLobby
import io.github.rotundtapir.fivehundred.net.Emote
import io.github.rotundtapir.fivehundred.net.EmoteReceived
import io.github.rotundtapir.fivehundred.net.ErrorCode
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
import io.github.rotundtapir.fivehundred.net.Distribution
import io.github.rotundtapir.fivehundred.net.Platform
import io.github.rotundtapir.fivehundred.net.RequestRematch
import io.github.rotundtapir.fivehundred.net.ResumedState
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
    // Where the session token survives this instance (web: the tab's sessionStorage), so a page
    // reload can resume its seat. The default keeps it in memory only.
    private val tokenStore: SessionTokenStore = SessionTokenStore.None,
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

    // Set when reconnecting lands us back in an existing room (the server resumed our session). We
    // hold the screen on the entry until the player chooses to rejoin or abandon, rather than
    // silently dropping them back into a game they may have meant to leave.
    private val _pendingRejoin = MutableStateFlow<ResumedState?>(null)
    val pendingRejoin: StateFlow<ResumedState?> = _pendingRejoin.asStateFlow()

    private val _emotes = MutableSharedFlow<EmoteReceived>(extraBufferCapacity = EMOTE_BUFFER)
    val emotes: SharedFlow<EmoteReceived> = _emotes.asSharedFlow()

    // A join code to prefill the join screen with (from a deep link); null on the manual join path.
    private val _pendingJoinCode = MutableStateFlow<String?>(null)
    val pendingJoinCode: StateFlow<String?> = _pendingJoinCode.asStateFlow()

    private var connectJob: Job? = null
    private var serverUrl: String = ""
    private var appVersion: String = ""
    private var platform: Platform = Platform.UNKNOWN
    private var buildFlavor: Distribution = Distribution.UNKNOWN
    private var commit: String = ""
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
    fun enter(
        serverUrl: String,
        appVersion: String,
        platform: Platform,
        buildFlavor: Distribution = Distribution.UNKNOWN,
        commit: String = "",
    ) {
        this.serverUrl = serverUrl.trim()
        this.appVersion = appVersion
        this.platform = platform
        this.buildFlavor = buildFlavor
        this.commit = commit
        _screen.value = OnlineScreen.ENTRY
        // Surface a bad address immediately instead of looping "Connecting…" forever on a URL that
        // can never open (a missing scheme is the common paste/typo).
        if (!isValidServerUrl(this.serverUrl)) {
            _errorMessage.value = "Invalid server address — it must start with ws:// or wss://"
            return
        }
        ensureConnected()
    }

    /**
     * Enter online mode from a deep link and go straight to the join screen with [code] prefilled.
     * The player still confirms/sets their name and taps Join there — we never auto-join.
     */
    fun enterWithJoinCode(
        serverUrl: String,
        appVersion: String,
        platform: Platform,
        code: String,
        buildFlavor: Distribution = Distribution.UNKNOWN,
        commit: String = "",
    ) {
        // Already in this very lobby/game (host or guest)? A link to it just returns you there,
        // rather than a Join screen where re-joining would be refused (you already hold a seat) —
        // which would otherwise strand the host on a dead-end screen.
        val current = _lobby.value
        if (current != null && current.joinCode.equals(code, ignoreCase = true)) {
            _pendingRejoin.value = null
            _pendingJoinCode.value = null
            applyLobbyPhase(current)
            return
        }
        enter(serverUrl, appVersion, platform, buildFlavor, commit)
        if (_errorMessage.value != null) return // bad server URL — stay on entry showing the error
        _pendingJoinCode.value = code
        _screen.value = OnlineScreen.JOIN
    }

    private fun isValidServerUrl(url: String): Boolean =
        (url.startsWith("ws://") || url.startsWith("wss://")) && url.length > "wss://".length

    private fun ensureConnected() {
        if (connectJob?.isActive == true) return
        intentionalDisconnect = false
        session.start()
        // The configured serverUrl is a host address; the protocol's WebSocket lives at /ws.
        val wsUrl = serverUrl.trimEnd('/') + WS_PATH
        connectJob = viewModelScope.launch {
            // A fresh instance (a web page reload) starts with no token in memory; presenting the
            // persisted one in the Hello is what lets the server resume our seat.
            if (sessionToken == null) sessionToken = tokenStore.load()
            var backoff = INITIAL_BACKOFF_MILLIS
            while (isActive && !intentionalDisconnect) {
                val hello = Hello(PROTOCOL_VERSION, appVersion, platform, sessionToken, buildFlavor, commit)
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
    fun goToCreate() {
        _errorMessage.value = null
        _screen.value = OnlineScreen.CREATE
    }
    fun goToJoin() {
        _errorMessage.value = null
        _pendingJoinCode.value = null // manual join: no prefill
        _screen.value = OnlineScreen.JOIN
    }
    fun backToEntry() {
        _errorMessage.value = null
        _pendingJoinCode.value = null
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
    ) {
        _errorMessage.value = null
        send(
            CreateLobby(
                displayName = displayName,
                playerCount = playerCount,
                teamCount = teamCount,
                turnTimeoutSeconds = turnTimeoutSeconds,
                idleDisbandMinutes = idleDisbandMinutes,
            ),
        )
    }

    fun joinLobby(code: String, displayName: String) {
        _errorMessage.value = null
        send(JoinLobby(code.trim(), displayName))
    }
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
        _pendingRejoin.value = null
        occupancy = emptyMap() // else a stale BOT_SUBSTITUTE would mislabel a seat in the next room
        session.reset()
        _screen.value = OnlineScreen.ENTRY
    }

    fun disbandLobby() = send(DisbandLobby)

    /** Fully exit online mode: leave any room, close the socket, and stop reconnecting. */
    fun exit() {
        intentionalDisconnect = true
        // Send the leave and only THEN tear down, so the server records a clean leave (freeing the
        // seat / clearing our binding) instead of a bare disconnect. Ordering the close after the
        // send in one coroutine is what makes the leave actually reach the wire.
        viewModelScope.launch {
            runCatching {
                withTimeout(LEAVE_GRACE_MILLIS) {
                    sessionReady.first { it }
                    client.send(LeaveLobby)
                }
            }
            connectJob?.cancel()
            runCatching { client.close() }
        }
        _lobby.value = null
        _gameOver.value = null
        _pendingRejoin.value = null
        occupancy = emptyMap()
        session.reset()
        _screen.value = OnlineScreen.ENTRY
    }

    // --- Game actions (optimistic) ----------------------------------------------------------------
    // Each move is checked against the current view's legal set, shown immediately (so play feels
    // instant), then sent. The server's echo confirms it; a reject reverts it (see onError).
    fun placeBid(bid: Bid) {
        val view = session.views.value ?: return
        if (!view.isMyTurn || bid !in view.legalBids) return
        optimisticSubmit(view.withOptimisticBid(bid), Action.PlaceBid(bid))
    }

    fun discard(cards: List<Card>) {
        val view = session.views.value ?: return
        if (view.mustDiscard != cards.size || !view.hand.containsAll(cards)) return
        optimisticSubmit(view.withOptimisticDiscard(cards), Action.ExchangeKitty(cards))
    }

    fun playCard(card: Card, nominate: Suit? = null) {
        val view = session.views.value ?: return
        if (!view.isMyTurn || card !in view.legalPlays) return
        optimisticSubmit(view.withOptimisticPlay(card, nominate), Action.PlayCard(card, nominate))
    }

    private fun optimisticSubmit(optimistic: PlayerView, action: Action) {
        val version = session.authoritativeStateVersion.value ?: return
        session.applyOptimistic(optimistic)
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
        viewModelScope.launch { tokenStore.save(welcome.sessionToken) }
        pendingSnapshot = welcome.resumed != null
        val resumed = welcome.resumed
        // A resumed session means the server put us back in a live room. Normally we offer the
        // choice (see [pendingRejoin]) — except when the resume is a formality: the room a
        // just-opened invite link points at (a host/guest reopening their own link, which on web is
        // a full reload), or the room this instance is already showing (a transient socket drop —
        // an app switch or a network blip — where "rejoin?" would interrupt a lobby/game the player
        // never left). In both cases drop straight back in; the room's LobbyState/ViewUpdate that
        // follows refreshes or navigates as needed.
        val expected = resumed != null && (
            resumed.joinCode.equals(_pendingJoinCode.value, ignoreCase = true) ||
                resumed.joinCode.equals(_lobby.value?.joinCode, ignoreCase = true)
            )
        if (expected) {
            _pendingJoinCode.value = null
            _pendingRejoin.value = null
        } else {
            _pendingRejoin.value = resumed
        }
        // If we thought we were in a room (we hold its lobby state) but the server can't resume us
        // (it disbanded/restarted while we were offline — state is in-memory), don't leave the
        // client frozen on a stale board: reset to entry with a banner. A connection that was never
        // in a room — including one sitting on a deep-linked Join screen — is untouched.
        if (resumed == null && _lobby.value != null) {
            _lobby.value = null
            _gameOver.value = null
            session.reset()
            occupancy = emptyMap()
            _errorMessage.value = "That game is no longer available."
            _screen.value = OnlineScreen.ENTRY
        }
        sessionReady.value = true
    }

    private fun onLobbyState(state: LobbyState) {
        _lobby.value = state
        recomputeSeatNames(state)
        // While the rejoin prompt is up, keep the lobby/game data flowing but hold the screen on the
        // entry — navigation happens only once the player confirms (see [confirmRejoin]).
        if (_pendingRejoin.value == null) applyLobbyPhase(state)
    }

    private fun applyLobbyPhase(state: LobbyState) {
        when (state.phase) {
            RoomPhase.PLAYING -> _screen.value = OnlineScreen.GAME
            RoomPhase.LOBBY, RoomPhase.FINISHED -> {
                if (_screen.value != OnlineScreen.LOBBY) session.reset()
                _screen.value = OnlineScreen.LOBBY
            }
        }
    }

    /** Accept the rejoin prompt: drop into the resumed lobby/game. */
    fun confirmRejoin() {
        _pendingRejoin.value = null
        _lobby.value?.let(::applyLobbyPhase)
    }

    /**
     * Decline the rejoin prompt and leave the resumed room for good: tell the server, then drop the
     * session token so a later reconnect starts fresh instead of resuming this room again.
     */
    fun abandonGame() {
        send(LeaveLobby)
        sessionToken = null
        viewModelScope.launch { tokenStore.save(null) }
        _pendingRejoin.value = null
        _lobby.value = null
        _gameOver.value = null
        session.reset()
        occupancy = emptyMap()
        _screen.value = OnlineScreen.ENTRY
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
        _pendingRejoin.value = null
        session.reset()
        occupancy = emptyMap()
        _errorMessage.value = "Lobby closed: ${message.reason.name.lowercase().replace('_', ' ')}"
        _screen.value = OnlineScreen.ENTRY
    }

    private fun onError(message: ErrorMessage) {
        // Only undo the optimistic view when the error actually concerns the *submitted move* — a
        // rejected/illegal/out-of-phase action. Errors that have nothing to do with our pending play
        // (a rate-limited emote, a lobby-action reject) must NOT yank a card back out of the trick;
        // the wire has no request id to correlate on, so we key off the error code instead. The next
        // authoritative view reconciles anything left over.
        if (message.code in ACTION_REJECTION_CODES) session.revertOptimistic()
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
        const val LEAVE_GRACE_MILLIS = 1500L

        /** Error codes that mean "your submitted move was rejected", so the optimistic view reverts. */
        val ACTION_REJECTION_CODES = setOf(
            ErrorCode.STALE_ACTION,
            ErrorCode.ILLEGAL_ACTION,
            ErrorCode.WRONG_PHASE,
        )
    }
}
