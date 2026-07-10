// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.SuitedCard
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.net.ClientMessage
import io.github.rotundtapir.fivehundred.net.ConnectionState
import io.github.rotundtapir.fivehundred.net.CreateLobby
import io.github.rotundtapir.fivehundred.net.ErrorCode
import io.github.rotundtapir.fivehundred.net.ErrorMessage
import io.github.rotundtapir.fivehundred.net.GameClient
import io.github.rotundtapir.fivehundred.net.Hello
import io.github.rotundtapir.fivehundred.net.LobbyConfig
import io.github.rotundtapir.fivehundred.net.LobbyState
import io.github.rotundtapir.fivehundred.net.OccupancyStatus
import io.github.rotundtapir.fivehundred.net.Platform
import io.github.rotundtapir.fivehundred.net.RoomPhase
import io.github.rotundtapir.fivehundred.net.SeatInfo
import io.github.rotundtapir.fivehundred.net.SeatStatus
import io.github.rotundtapir.fivehundred.net.ServerMessage
import io.github.rotundtapir.fivehundred.net.ViewUpdate
import io.github.rotundtapir.fivehundred.net.Welcome
import io.github.rotundtapir.fivehundred.online.JoinLink
import io.github.rotundtapir.fivehundred.online.OnlineGameSession
import io.github.rotundtapir.fivehundred.online.OnlineScreen
import io.github.rotundtapir.fivehundred.online.OnlineViewModel
import io.github.rotundtapir.fivehundred.online.SessionTokenStore
import io.github.rotundtapir.fivehundred.online.withOptimisticDiscard
import io.github.rotundtapir.fivehundred.online.withOptimisticPlay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A minimal [PlayerView] for pacing/session tests; only the shape-defining fields are meaningful. */
internal fun testView(
    seat: Seat = Seat(0),
    phase: Phase = Phase.BIDDING,
    handNumber: Int = 1,
    toAct: Seat? = Seat(0),
    biddingHistory: List<Pair<Seat, io.github.rotundtapir.fivehundred.engine.Bid>> = emptyList(),
    currentTrick: List<io.github.rotundtapir.fivehundred.engine.TrickPlay> = emptyList(),
    trickNumber: Int = 0,
    lastHandResult: io.github.rotundtapir.fivehundred.engine.HandResult? = null,
    winner: Int? = null,
): PlayerView = PlayerView(
    seat = seat,
    phase = phase,
    playerCount = 4,
    teamCount = 2,
    handNumber = handNumber,
    hand = emptyList(),
    handSizes = emptyMap(),
    dealer = Seat(0),
    scores = mapOf(0 to 0, 1 to 0),
    toAct = toAct,
    biddingHistory = biddingHistory,
    highBid = null,
    highBidder = null,
    legalBids = emptyList(),
    contract = null,
    trump = null,
    leader = null,
    currentTrick = currentTrick,
    ledSuit = null,
    lastTrick = null,
    tricksWon = emptyMap(),
    trickNumber = trickNumber,
    legalPlays = emptyList(),
    mustDiscard = 0,
    exposedDeclarerHand = null,
    activeSeats = listOf(Seat(0), Seat(1), Seat(2), Seat(3)),
    lastHandResult = lastHandResult,
    winner = winner,
)

class PacingGatesTest {

    @Test
    fun `off is inert - no gate suspends`() = runTest {
        val gates = PacingGates(MutableStateFlow(AnimationSpeed.OFF), MutableStateFlow(false))
        var done = false
        val job = launch { gates.awaitGates(testView()); done = true }
        runCurrent()
        assertTrue(job.isCompleted && done, "OFF must not suspend on a fresh-hand view")
    }

    @Test
    fun `deal gate waits for the deal-animation signal at NORMAL`() = runTest {
        val gates = PacingGates(MutableStateFlow(AnimationSpeed.NORMAL), MutableStateFlow(false))
        var done = false
        val job = launch { gates.awaitGates(testView(handNumber = 1)); done = true }
        advanceTimeBy(50)
        runCurrent()
        assertFalse(done, "should still be waiting for the deal signal")
        gates.dealAnimationFinished(1)
        advanceUntilIdle()
        assertTrue(done, "deal signal should release the gate")
        job.cancel()
    }

    @Test
    fun `preAcknowledge lets a snapshot pass the deal gate without a signal`() = runTest {
        val gates = PacingGates(MutableStateFlow(AnimationSpeed.NORMAL), MutableStateFlow(false))
        val view = testView(handNumber = 2)
        gates.preAcknowledge(view)
        var done = false
        val job = launch { gates.awaitGates(view); done = true }
        advanceUntilIdle()
        assertTrue(done, "a pre-acknowledged view must not block on the deal signal")
        job.cancel()
    }
}

class OnlineGameSessionTest {

    @Test
    fun `publishes views in order and tracks stateVersion`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val gates = PacingGates(MutableStateFlow(AnimationSpeed.OFF), MutableStateFlow(false))
        val session = OnlineGameSession(gates, scope)
        session.start()
        session.offer(ViewUpdate(1, testView(handNumber = 1), turnRemainingMillis = null), snapshot = true)
        session.offer(ViewUpdate(2, testView(handNumber = 1, phase = Phase.KITTY), turnRemainingMillis = null), false)
        advanceUntilIdle()
        assertEquals(2, session.authoritativeStateVersion.value)
        assertEquals(Phase.KITTY, session.views.value?.phase)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `snapshot bypasses the deal gate at NORMAL`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val gates = PacingGates(MutableStateFlow(AnimationSpeed.NORMAL), MutableStateFlow(false))
        val session = OnlineGameSession(gates, scope)
        session.start()
        // A hand-start view would normally wait for the deal signal; as a snapshot it publishes now.
        session.offer(ViewUpdate(5, testView(handNumber = 1), turnRemainingMillis = null), snapshot = true)
        advanceUntilIdle()
        assertEquals(5, session.authoritativeStateVersion.value)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `applyOptimistic shows the move at once, keeps the authoritative version, and revert restores it`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val gates = PacingGates(MutableStateFlow(AnimationSpeed.OFF), MutableStateFlow(false))
        val session = OnlineGameSession(gates, scope)
        session.start()
        session.offer(ViewUpdate(7, testView(phase = Phase.PLAY, toAct = Seat(0)), turnRemainingMillis = null), snapshot = true)
        advanceUntilIdle()
        assertEquals(Seat(0), session.views.value?.toAct)

        // Optimistically apply our move: it shows immediately, but the version we'd submit against
        // must stay the *server's* (7), not change with the client-only projection.
        session.applyOptimistic(testView(phase = Phase.PLAY, toAct = Seat(1)))
        assertEquals(Seat(1), session.views.value?.toAct, "optimistic view is shown at once")
        assertEquals(7, session.authoritativeStateVersion.value, "optimistic view must not change the version")

        session.revertOptimistic()
        assertEquals(Seat(0), session.views.value?.toAct, "revert restores the last authoritative view")
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `revertOptimistic is a no-op when nothing is pending`() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val gates = PacingGates(MutableStateFlow(AnimationSpeed.OFF), MutableStateFlow(false))
        val session = OnlineGameSession(gates, scope)
        session.start()
        session.offer(ViewUpdate(2, testView(toAct = Seat(3)), turnRemainingMillis = null), snapshot = true)
        advanceUntilIdle()
        session.revertOptimistic() // no optimistic move outstanding
        assertEquals(Seat(3), session.views.value?.toAct, "a stray revert must not disturb the view")
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}

class OptimisticViewTest {

    private val card = SuitedCard(Rank.ACE, Suit.SPADES)

    @Test
    fun `withOptimisticPlay removes the card, appends to the trick, and clears legal plays`() {
        val view = testView(seat = Seat(0), phase = Phase.PLAY, toAct = Seat(0))
            .copy(hand = listOf(card), legalPlays = listOf(card))
        val projected = view.withOptimisticPlay(card, nominate = null)
        assertFalse(card in projected.hand, "played card leaves the hand")
        assertEquals(1, projected.currentTrick.size, "the card appears on the table")
        assertTrue(projected.legalPlays.isEmpty(), "no further plays offered until the server view")
        assertTrue(projected.toAct != Seat(0), "it stops being our turn")
    }

    @Test
    fun `withOptimisticDiscard removes the cards and zeroes the discard requirement`() {
        val view = testView(seat = Seat(0), phase = Phase.KITTY).copy(hand = listOf(card), mustDiscard = 1)
        val projected = view.withOptimisticDiscard(listOf(card))
        assertFalse(card in projected.hand)
        assertEquals(0, projected.mustDiscard)
    }
}

class JoinLinkTest {

    @Test
    fun `forCode builds the canonical web invite URL, uppercased`() {
        assertEquals("https://rotundtapir.github.io/500/?joinCode=12AB", JoinLink.forCode("12AB"))
        assertEquals("https://rotundtapir.github.io/500/?joinCode=12AB", JoinLink.forCode(" 12ab "))
    }

    @Test
    fun `normalizeCode accepts 4 alphanumerics and rejects junk`() {
        assertEquals("12AB", JoinLink.normalizeCode("12ab"))
        assertEquals("12AB", JoinLink.normalizeCode("12-AB")) // strips separators
        assertEquals(null, JoinLink.normalizeCode("ABC"))       // too short
        assertEquals(null, JoinLink.normalizeCode("ABCDE"))     // too long
        assertEquals(null, JoinLink.normalizeCode(""))
        assertEquals(null, JoinLink.normalizeCode(null))
    }
}

class OnlineViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeGameClient : GameClient {
        val sent = mutableListOf<ClientMessage>()
        private val _incoming = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 64)
        override val incoming: SharedFlow<ServerMessage> = _incoming.asSharedFlow()
        val stateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)
        override val state: StateFlow<ConnectionState> = stateFlow
        private val closed = CompletableDeferred<Unit>()

        override suspend fun run(serverUrl: String) {
            stateFlow.value = ConnectionState.CONNECTED
            runCatching { closed.await() }
            stateFlow.value = ConnectionState.CLOSED
        }

        override suspend fun send(message: ClientMessage) { sent += message }
        override suspend fun close() { closed.complete(Unit) }
        fun push(message: ServerMessage) { _incoming.tryEmit(message) }
    }

    private fun lobby(phase: RoomPhase, seats: List<SeatInfo>, yourSeat: Seat? = Seat(0)) = LobbyState(
        joinCode = "AB12",
        gameId = "game-1",
        config = LobbyConfig(playerCount = 4, teamCount = 2),
        seats = seats,
        creatorSeat = Seat(0),
        yourSeat = yourSeat,
        phase = phase,
    )

    private fun seat(i: Int, name: String, ready: Boolean = false, bot: Boolean = false, connected: Boolean = true) =
        SeatInfo(Seat(i), name, isBot = bot, ready = ready, connected = connected)

    @Test
    fun `connect sends hello with version and platform`() = runTest(dispatcher) {
        val client = FakeGameClient()
        val vm = OnlineViewModel(client)
        vm.enter("ws://localhost", "0.3.0", Platform.WEB)
        advanceUntilIdle()
        val hello = client.sent.filterIsInstance<Hello>().single()
        assertEquals("0.3.0", hello.appVersion)
        assertEquals(Platform.WEB, hello.platform)
        assertEquals(null, hello.sessionToken)
        vm.exit()
    }

    @Test
    fun `lobby state moves the screen to lobby then game`() = runTest(dispatcher) {
        val client = FakeGameClient()
        val vm = OnlineViewModel(client)
        vm.enter("ws://localhost", "0.3.0", Platform.WEB)
        advanceUntilIdle()
        client.push(Welcome("tok", "0.3.0"))
        vm.createLobby("Alice", playerCount = 4, teamCount = 2, turnTimeoutSeconds = 45, idleDisbandMinutes = 30)
        advanceUntilIdle()
        assertTrue(client.sent.any { it is CreateLobby })

        client.push(lobby(RoomPhase.LOBBY, listOf(seat(0, "Alice", ready = true))))
        advanceUntilIdle()
        assertEquals(OnlineScreen.LOBBY, vm.screen.value)

        client.push(lobby(RoomPhase.PLAYING, listOf(seat(0, "Alice"), seat(1, "Bruce (bot)", bot = true))))
        advanceUntilIdle()
        assertEquals(OnlineScreen.GAME, vm.screen.value)
        vm.exit()
    }

    @Test
    fun `seat substitution appends the bot suffix to the name map`() = runTest(dispatcher) {
        val client = FakeGameClient()
        val vm = OnlineViewModel(client)
        vm.enter("ws://localhost", "0.3.0", Platform.WEB)
        advanceUntilIdle()
        client.push(Welcome("tok", "0.3.0"))
        client.push(lobby(RoomPhase.PLAYING, listOf(seat(0, "Alice"), seat(1, "Bruce"))))
        client.push(SeatStatus(Seat(1), OccupancyStatus.BOT_SUBSTITUTE))
        advanceUntilIdle()
        assertEquals("Bruce (bot)", vm.seatNames.value[Seat(1)])
        assertEquals("Alice", vm.seatNames.value[Seat(0)])
        vm.exit()
    }

    @Test
    fun `a resume welcome flags the next view as a snapshot`() = runTest(dispatcher) {
        val client = FakeGameClient()
        val vm = OnlineViewModel(client)
        vm.enter("ws://localhost", "0.3.0", Platform.WEB)
        advanceUntilIdle()
        client.push(Welcome("tok", "0.3.0", resumed = io.github.rotundtapir.fivehundred.net.ResumedState("AB12", RoomPhase.PLAYING)))
        client.push(lobby(RoomPhase.PLAYING, listOf(seat(0, "Alice"), seat(1, "Bruce (bot)", bot = true))))
        client.push(ViewUpdate(9, testView(handNumber = 3, phase = Phase.PLAY, trickNumber = 4), turnRemainingMillis = 20_000))
        advanceUntilIdle()
        // The snapshot published immediately (no deal signal was ever sent) at NORMAL speed.
        assertEquals(9, vm.session.authoritativeStateVersion.value)
        vm.exit()
    }

    @Test
    fun `a stale-action error reverts the optimistic play but a rate-limit error does not`() = runTest(dispatcher) {
        val client = FakeGameClient()
        val vm = OnlineViewModel(client)
        vm.enter("ws://localhost", "0.3.0", Platform.WEB)
        advanceUntilIdle()
        client.push(Welcome("tok", "0.3.0"))
        val card = SuitedCard(Rank.ACE, Suit.SPADES)
        val myTurn = testView(seat = Seat(0), phase = Phase.PLAY, toAct = Seat(0)).copy(hand = listOf(card), legalPlays = listOf(card))
        client.push(ViewUpdate(5, myTurn, turnRemainingMillis = null))
        advanceUntilIdle()

        vm.playCard(card) // optimistic: the card should leave the hand immediately
        advanceUntilIdle()
        assertFalse(card in vm.session.views.value!!.hand, "the play is shown optimistically")

        // A rate-limit error is unrelated to the submitted move — it must NOT yank the card back.
        client.push(ErrorMessage(ErrorCode.RATE_LIMITED, "Slow down"))
        advanceUntilIdle()
        assertFalse(card in vm.session.views.value!!.hand, "an unrelated error must not revert the play")

        // A stale-action error means the move was rejected — now the optimistic view reverts.
        client.push(ErrorMessage(ErrorCode.STALE_ACTION, "stale"))
        advanceUntilIdle()
        assertTrue(card in vm.session.views.value!!.hand, "a rejected move reverts")
        vm.exit()
    }

    @Test
    fun `a resume the server cannot honour on the game screen resets to entry with a banner`() = runTest(dispatcher) {
        val client = FakeGameClient()
        val vm = OnlineViewModel(client)
        vm.enter("ws://localhost", "0.3.0", Platform.WEB)
        advanceUntilIdle()
        client.push(Welcome("tok", "0.3.0"))
        client.push(lobby(RoomPhase.PLAYING, listOf(seat(0, "Alice"), seat(1, "Bruce (bot)", bot = true))))
        advanceUntilIdle()
        assertEquals(OnlineScreen.GAME, vm.screen.value)
        // Reconnect: the room is gone, so the server can't resume us. Rather than freeze on the stale
        // board, the client must drop back to entry and explain.
        client.push(Welcome("tok2", "0.3.0", resumed = null))
        advanceUntilIdle()
        assertEquals(OnlineScreen.ENTRY, vm.screen.value)
        assertNotNull(vm.errorMessage.value)
        vm.exit()
    }

    @Test
    fun `enterWithJoinCode opens the join screen with the code prefilled`() = runTest(dispatcher) {
        val client = FakeGameClient()
        val vm = OnlineViewModel(client)
        vm.enterWithJoinCode("ws://localhost", "0.3.0", Platform.WEB, "12AB")
        advanceUntilIdle()
        assertEquals(OnlineScreen.JOIN, vm.screen.value)
        assertEquals("12AB", vm.pendingJoinCode.value)
        // A manual navigation to join clears the prefill (no stale code from an earlier link).
        vm.goToJoin()
        assertEquals(null, vm.pendingJoinCode.value)
        vm.exit()
    }

    @Test
    fun `enterWithJoinCode with a bad server URL stays on entry and does not prefill`() = runTest(dispatcher) {
        val client = FakeGameClient()
        val vm = OnlineViewModel(client)
        vm.enterWithJoinCode("not-a-ws-url", "0.3.0", Platform.WEB, "12AB")
        advanceUntilIdle()
        assertEquals(OnlineScreen.ENTRY, vm.screen.value)
        assertEquals(null, vm.pendingJoinCode.value)
        assertNotNull(vm.errorMessage.value)
    }

    private class FakeTokenStore(var token: String? = null) : SessionTokenStore {
        override suspend fun load(): String? = token
        override suspend fun save(token: String?) { this.token = token }
    }

    @Test
    fun `the welcome's session token is persisted and a fresh instance reuses it`() = runTest(dispatcher) {
        val store = FakeTokenStore()
        val client = FakeGameClient()
        val vm = OnlineViewModel(client, store)
        vm.enter("ws://localhost", "0.3.0", Platform.WEB)
        advanceUntilIdle()
        client.push(Welcome("tok", "0.3.0"))
        advanceUntilIdle()
        assertEquals("tok", store.token, "the issued token must be persisted")
        vm.exit()
        advanceUntilIdle()

        // A brand-new ViewModel over the same store — a web page reload — presents the persisted
        // token in its Hello, which is what lets the server resume the seat (issue #11).
        val client2 = FakeGameClient()
        val vm2 = OnlineViewModel(client2, store)
        vm2.enter("ws://localhost", "0.3.0", Platform.WEB)
        advanceUntilIdle()
        assertEquals("tok", client2.sent.filterIsInstance<Hello>().single().sessionToken)
        vm2.exit()
    }

    @Test
    fun `abandoning the resumed room clears the persisted token`() = runTest(dispatcher) {
        val store = FakeTokenStore(token = "tok")
        val client = FakeGameClient()
        val vm = OnlineViewModel(client, store)
        vm.enter("ws://localhost", "0.3.0", Platform.WEB)
        advanceUntilIdle()
        client.push(
            Welcome("tok", "0.3.0", resumed = io.github.rotundtapir.fivehundred.net.ResumedState("AB12", RoomPhase.LOBBY)),
        )
        advanceUntilIdle()
        assertNotNull(vm.pendingRejoin.value, "the resumed room is offered back")
        vm.abandonGame()
        advanceUntilIdle()
        assertEquals(null, store.token, "declining the rejoin must erase the token")
        assertEquals(OnlineScreen.ENTRY, vm.screen.value)
        vm.exit()
    }

    @Test
    fun `a fresh connection's welcome does not kick the deep-linked join screen back to entry`() = runTest(dispatcher) {
        val client = FakeGameClient()
        val vm = OnlineViewModel(client)
        vm.enterWithJoinCode("ws://localhost", "0.3.0", Platform.WEB, "12AB")
        advanceUntilIdle()
        assertEquals(OnlineScreen.JOIN, vm.screen.value)
        // The handshake completes with nothing to resume — we were never in a room, so the Join
        // screen (and its prefilled code) must survive rather than reset with a stale-game banner.
        client.push(Welcome("tok", "0.3.0"))
        advanceUntilIdle()
        assertEquals(OnlineScreen.JOIN, vm.screen.value)
        assertEquals("12AB", vm.pendingJoinCode.value)
        assertEquals(null, vm.errorMessage.value)
        vm.exit()
    }

    @Test
    fun `a deep link whose code matches the resumed room drops straight back in without a prompt`() = runTest(dispatcher) {
        val store = FakeTokenStore(token = "tok")
        val client = FakeGameClient()
        val vm = OnlineViewModel(client, store)
        // The host reopened their own invite link after a reload: the persisted token resumes the
        // very room the link points at.
        vm.enterWithJoinCode("ws://localhost", "0.3.0", Platform.WEB, "ab12")
        advanceUntilIdle()
        client.push(
            Welcome("tok", "0.3.0", resumed = io.github.rotundtapir.fivehundred.net.ResumedState("AB12", RoomPhase.LOBBY)),
        )
        client.push(lobby(RoomPhase.LOBBY, listOf(seat(0, "Alice"))))
        advanceUntilIdle()
        assertEquals(null, vm.pendingRejoin.value, "no rejoin prompt on the way into the linked room")
        assertEquals(null, vm.pendingJoinCode.value)
        assertEquals(OnlineScreen.LOBBY, vm.screen.value, "straight back into the lobby")
        vm.exit()
    }

    @Test
    fun `a transient reconnect into the room already on screen does not prompt`() = runTest(dispatcher) {
        val client = FakeGameClient()
        val vm = OnlineViewModel(client)
        vm.enter("ws://localhost", "0.3.0", Platform.WEB)
        advanceUntilIdle()
        client.push(Welcome("tok", "0.3.0"))
        client.push(lobby(RoomPhase.LOBBY, listOf(seat(0, "Alice", ready = true))))
        advanceUntilIdle()
        assertEquals(OnlineScreen.LOBBY, vm.screen.value)

        // The socket blips (an app switch, a network drop) and the reconnect resumes the very room
        // we're still displaying — asking "rejoin?" would interrupt a lobby the player never left.
        client.push(Welcome("tok", "0.3.0", resumed = io.github.rotundtapir.fivehundred.net.ResumedState("AB12", RoomPhase.LOBBY)))
        advanceUntilIdle()
        assertEquals(null, vm.pendingRejoin.value, "no prompt for the room already on screen")
        assertEquals(OnlineScreen.LOBBY, vm.screen.value)
        vm.exit()
    }

    @Test
    fun `a resume into a different room than the link still asks before rejoining`() = runTest(dispatcher) {
        val store = FakeTokenStore(token = "tok")
        val client = FakeGameClient()
        val vm = OnlineViewModel(client, store)
        vm.enterWithJoinCode("ws://localhost", "0.3.0", Platform.WEB, "XY99")
        advanceUntilIdle()
        client.push(
            Welcome("tok", "0.3.0", resumed = io.github.rotundtapir.fivehundred.net.ResumedState("AB12", RoomPhase.LOBBY)),
        )
        advanceUntilIdle()
        assertNotNull(vm.pendingRejoin.value, "a resume into a different room keeps the prompt")
        assertEquals("XY99", vm.pendingJoinCode.value, "the link's code stays prefilled")
        vm.exit()
    }

    @Test
    fun `a deep link to the lobby you're already in returns to it, not the join screen`() = runTest(dispatcher) {
        val client = FakeGameClient()
        val vm = OnlineViewModel(client)
        vm.enter("ws://localhost", "0.3.0", Platform.WEB)
        advanceUntilIdle()
        client.push(Welcome("tok", "0.3.0"))
        // We're the host, sitting in lobby AB12 (the lobby() helper's join code).
        client.push(lobby(RoomPhase.LOBBY, listOf(seat(0, "Alice", ready = true))))
        advanceUntilIdle()
        assertEquals(OnlineScreen.LOBBY, vm.screen.value)

        // Tapping our own invite link (same code, any case) returns us to the lobby — no Join screen,
        // no prefilled code that would be refused as "already seated".
        vm.enterWithJoinCode("ws://localhost", "0.3.0", Platform.WEB, "ab12")
        advanceUntilIdle()
        assertEquals(OnlineScreen.LOBBY, vm.screen.value)
        assertEquals(null, vm.pendingJoinCode.value)

        // A link to a *different* game still routes to the prefilled Join screen.
        vm.enterWithJoinCode("ws://localhost", "0.3.0", Platform.WEB, "XY99")
        advanceUntilIdle()
        assertEquals(OnlineScreen.JOIN, vm.screen.value)
        assertEquals("XY99", vm.pendingJoinCode.value)
        vm.exit()
    }
}
