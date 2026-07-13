// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.net

import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.SuitedCard
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.Phase
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.engine.Trump
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the wire format. These goldens are a compatibility contract: if a change to a message shape,
 * a `@SerialName`, or the [WireJson] config alters the emitted JSON, this test fails loudly rather
 * than shipping a silent protocol break.
 */
class ProtocolGoldenTest {

    private inline fun <reified T : ClientMessage> roundTripClient(msg: T) {
        val json = WireJson.encodeToString<ClientMessage>(msg)
        assertEquals(msg, WireJson.decodeFromString<ClientMessage>(json), "client round-trip: $json")
    }

    private inline fun <reified T : ServerMessage> roundTripServer(msg: T) {
        val json = WireJson.encodeToString<ServerMessage>(msg)
        assertEquals(msg, WireJson.decodeFromString<ServerMessage>(json), "server round-trip: $json")
    }

    @Test
    fun `client messages golden shapes`() {
        assertEquals(
            """{"type":"hello","protocolVersion":1,"appVersion":"0.3.0","platform":"android"}""",
            WireJson.encodeToString<ClientMessage>(
                Hello(PROTOCOL_VERSION, "0.3.0", Platform.ANDROID),
            ),
        )
        // The build-telemetry fields are omitted at their defaults (above) and named as below when set.
        assertEquals(
            """{"type":"hello","protocolVersion":1,"appVersion":"0.3.0","platform":"android",""" +
                """"buildFlavor":"foss","commit":"6f7e099"}""",
            WireJson.encodeToString<ClientMessage>(
                Hello(PROTOCOL_VERSION, "0.3.0", Platform.ANDROID, buildFlavor = Distribution.FOSS, commit = "6f7e099"),
            ),
        )
        assertEquals(
            """{"type":"lobby.join","code":"AB12","displayName":"Alice"}""",
            WireJson.encodeToString<ClientMessage>(JoinLobby("AB12", "Alice")),
        )
        assertEquals(
            """{"type":"lobby.pickSeat","seat":2}""",
            WireJson.encodeToString<ClientMessage>(PickSeat(Seat(2))),
        )
        assertEquals(
            """{"type":"lobby.start"}""",
            WireJson.encodeToString<ClientMessage>(StartGame),
        )
        assertEquals(
            """{"type":"emote","emote":"wellPlayed"}""",
            WireJson.encodeToString<ClientMessage>(SendEmote(Emote.WELL_PLAYED)),
        )
        assertEquals(
            """{"type":"game.action","stateVersion":5,""" +
                """"action":{"type":"placeBid","bid":{"type":"named","level":7,"trump":"HEARTS"}}}""",
            WireJson.encodeToString<ClientMessage>(
                SubmitAction(5, Action.PlaceBid(Bid.Named(7, Trump.HEARTS))),
            ),
        )
    }

    @Test
    fun `server messages golden shapes`() {
        assertEquals(
            """{"type":"error","code":"badName","message":"try again"}""",
            WireJson.encodeToString<ServerMessage>(ErrorMessage(ErrorCode.BAD_NAME, "try again")),
        )
        assertEquals(
            """{"type":"game.seatStatus","seat":1,"status":"botSubstitute"}""",
            WireJson.encodeToString<ServerMessage>(SeatStatus(Seat(1), OccupancyStatus.BOT_SUBSTITUTE)),
        )
        assertEquals(
            """{"type":"game.over","winnerTeam":0,"scores":{"0":520,"1":260}}""",
            WireJson.encodeToString<ServerMessage>(GameOver(0, mapOf(0 to 520, 1 to 260))),
        )
    }

    @Test
    fun `all message types round-trip`() {
        roundTripClient(Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB, sessionToken = "tok"))
        roundTripClient(
            Hello(PROTOCOL_VERSION, "0.3.0", Platform.WEB, buildFlavor = Distribution.WEB, commit = "abc1234"),
        )
        roundTripClient(CreateLobby("Bob", playerCount = 4, teamCount = 2, seed = 42L))
        roundTripClient(JoinLobby("cd34", "Carol"))
        roundTripClient(SetName("Dave"))
        roundTripClient(PickSeat(Seat(3)))
        roundTripClient(SetReady(true))
        roundTripClient(ConfigureLobby(turnTimeoutSeconds = 60))
        roundTripClient(StartGame)
        roundTripClient(LeaveLobby)
        roundTripClient(DisbandLobby)
        roundTripClient(RequestRematch)
        roundTripClient(SubmitAction(1, Action.PlayCard(SuitedCard(Rank.ACE, Suit.SPADES))))
        roundTripClient(SubmitAction(2, Action.ExchangeKitty(listOf(SuitedCard(Rank.TWO, Suit.CLUBS)))))
        roundTripClient(SendEmote(Emote.GOOD_GAME))

        roundTripServer(Welcome("tok", "0.3.0", ResumedState("AB12", RoomPhase.PLAYING)))
        roundTripServer(UpdateRequired("0.3.0", "please update"))
        roundTripServer(
            LobbyState(
                joinCode = "AB12",
                gameId = "ab12cdef-0000",
                config = LobbyConfig(playerCount = 4, teamCount = 2),
                seats = listOf(SeatInfo(Seat(0), "Alice", isBot = false, ready = true, connected = true)),
                creatorSeat = Seat(0),
                yourSeat = Seat(0),
                phase = RoomPhase.LOBBY,
            ),
        )
        roundTripServer(ViewUpdate(7, sampleView(), turnRemainingMillis = 30_000))
        roundTripServer(SeatStatus(Seat(2), OccupancyStatus.HUMAN))
        roundTripServer(GameOver(1, mapOf(0 to 100, 1 to 500)))
        roundTripServer(EmoteReceived(Seat(1), Emote.OOPS))
        roundTripServer(LobbyDisbanded(DisbandReason.IDLE_TIMEOUT))
        roundTripServer(ErrorMessage(ErrorCode.RATE_LIMITED, "slow down", fatal = false))
    }

    @Test
    fun `unknown fields are ignored for forward compatibility`() {
        val json = """{"type":"lobby.join","code":"AB12","displayName":"Alice","futureField":123}"""
        assertEquals(JoinLobby("AB12", "Alice"), WireJson.decodeFromString<ClientMessage>(json))
    }

    @Test
    fun `unknown enum values coerce to UNKNOWN so new emotes never break old clients`() {
        val json = """{"type":"emote","seat":0,"emote":"cartwheel"}"""
        val decoded = WireJson.decodeFromString<ServerMessage>(json)
        assertEquals(EmoteReceived(Seat(0), Emote.UNKNOWN), decoded)
    }

    @Test
    fun `Seat serializes as a bare int and works as a JSON map key`() {
        val json = WireJson.encodeToString<ServerMessage>(ViewUpdate(1, sampleView()))
        assertTrue(json.contains(""""seat":0"""), "Seat should be a bare int: $json")
        assertTrue(json.contains(""""handSizes":{"0":10,"1":10}"""), "Seat map key should be a string int: $json")
    }

    private fun sampleView(): PlayerView = PlayerView(
        seat = Seat(0),
        phase = Phase.BIDDING,
        playerCount = 2,
        teamCount = 2,
        handNumber = 1,
        hand = listOf(SuitedCard(Rank.ACE, Suit.SPADES), SuitedCard(Rank.KING, Suit.HEARTS)),
        handSizes = mapOf(Seat(0) to 10, Seat(1) to 10),
        dealer = Seat(1),
        scores = mapOf(0 to 0, 1 to 0),
        toAct = Seat(0),
        biddingHistory = emptyList(),
        highBid = null,
        highBidder = null,
        legalBids = listOf(Bid.Pass, Bid.Named(6, Trump.SPADES)),
        contract = null,
        trump = null,
        leader = null,
        currentTrick = emptyList(),
        ledSuit = null,
        lastTrick = null,
        tricksWon = emptyMap(),
        trickNumber = 0,
        legalPlays = emptyList(),
        mustDiscard = 0,
        exposedDeclarerHand = null,
        activeSeats = listOf(Seat(0), Seat(1)),
        lastHandResult = null,
        winner = null,
    )
}
