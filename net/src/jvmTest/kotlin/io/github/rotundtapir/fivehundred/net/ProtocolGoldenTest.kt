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
import kotlin.test.assertFalse
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

    // The goldens above cover the shapes most likely to be edited by hand. The ones below complete
    // the set, so that *every* message type is pinned by bytes rather than only by round-tripping —
    // a round-trip stays green through a rename or a reordering that would break a released client.

    @Test
    fun `every remaining client message shape is pinned`() {
        assertEquals(
            """{"type":"lobby.create","displayName":"Bob","playerCount":4,"teamCount":2}""",
            WireJson.encodeToString<ClientMessage>(CreateLobby("Bob", playerCount = 4, teamCount = 2)),
            "defaults must stay omitted: that is what makes adding an optional field non-breaking",
        )
        assertEquals(
            """{"type":"lobby.create","displayName":"Bob","playerCount":6,"teamCount":3,""" +
                """"misereEnabled":false,"noTrumpsEnabled":false,"turnTimeoutSeconds":60,""" +
                """"idleDisbandMinutes":30,"seed":42}""",
            WireJson.encodeToString<ClientMessage>(
                CreateLobby(
                    "Bob",
                    playerCount = 6,
                    teamCount = 3,
                    misereEnabled = false,
                    noTrumpsEnabled = false,
                    turnTimeoutSeconds = 60,
                    idleDisbandMinutes = 30,
                    seed = 42L,
                ),
            ),
        )
        assertEquals(
            """{"type":"lobby.setName","displayName":"Dave"}""",
            WireJson.encodeToString<ClientMessage>(SetName("Dave")),
        )
        assertEquals(
            """{"type":"lobby.ready","ready":true}""",
            WireJson.encodeToString<ClientMessage>(SetReady(true)),
        )
        assertEquals(
            """{"type":"lobby.configure","turnTimeoutSeconds":60}""",
            WireJson.encodeToString<ClientMessage>(ConfigureLobby(turnTimeoutSeconds = 60)),
        )
        assertEquals(
            """{"type":"lobby.configure","turnTimeoutSeconds":60,"idleDisbandMinutes":30}""",
            WireJson.encodeToString<ClientMessage>(
                ConfigureLobby(turnTimeoutSeconds = 60, idleDisbandMinutes = 30),
            ),
        )
        assertEquals("""{"type":"lobby.leave"}""", WireJson.encodeToString<ClientMessage>(LeaveLobby))
        assertEquals("""{"type":"lobby.disband"}""", WireJson.encodeToString<ClientMessage>(DisbandLobby))
        assertEquals("""{"type":"lobby.rematch"}""", WireJson.encodeToString<ClientMessage>(RequestRematch))
        // A card inside an action carries a fully-qualified discriminator, because cardkit's Card
        // hierarchy has no @SerialName. Verbose, but it is what released clients speak — giving those
        // types short names would be a breaking change needing a PROTOCOL_VERSION bump.
        assertEquals(
            """{"type":"game.action","stateVersion":1,"action":{"type":"playCard","card":""" +
                """{"type":"io.github.rotundtapir.cardkit.core.SuitedCard","rank":"ACE","suit":"SPADES"}}}""",
            WireJson.encodeToString<ClientMessage>(
                SubmitAction(1, Action.PlayCard(SuitedCard(Rank.ACE, Suit.SPADES))),
            ),
        )
        assertEquals(
            """{"type":"game.action","stateVersion":2,"action":{"type":"exchangeKitty","discards":[""" +
                """{"type":"io.github.rotundtapir.cardkit.core.SuitedCard","rank":"TWO","suit":"CLUBS"}]}}""",
            WireJson.encodeToString<ClientMessage>(
                SubmitAction(2, Action.ExchangeKitty(listOf(SuitedCard(Rank.TWO, Suit.CLUBS)))),
            ),
        )
    }

    @Test
    fun `every remaining server message shape is pinned`() {
        assertEquals(
            """{"type":"welcome","sessionToken":"tok","serverVersion":"0.3.0"}""",
            WireJson.encodeToString<ServerMessage>(Welcome("tok", "0.3.0")),
        )
        assertEquals(
            """{"type":"welcome","sessionToken":"tok","serverVersion":"0.3.0",""" +
                """"resumed":{"joinCode":"AB12","phase":"playing"}}""",
            WireJson.encodeToString<ServerMessage>(
                Welcome("tok", "0.3.0", ResumedState("AB12", RoomPhase.PLAYING)),
            ),
        )
        assertEquals(
            """{"type":"updateRequired","minAppVersion":"0.3.0","message":"please update"}""",
            WireJson.encodeToString<ServerMessage>(UpdateRequired("0.3.0", "please update")),
        )
        assertEquals(
            """{"type":"lobby.state","joinCode":"AB12","gameId":"ab12cdef-0000",""" +
                """"config":{"playerCount":4,"teamCount":2},""" +
                """"seats":[{"seat":0,"name":"Alice","isBot":false,"ready":true,"connected":true}],""" +
                """"creatorSeat":0,"yourSeat":0,"phase":"lobby"}""",
            WireJson.encodeToString<ServerMessage>(
                LobbyState(
                    joinCode = "AB12",
                    gameId = "ab12cdef-0000",
                    config = LobbyConfig(playerCount = 4, teamCount = 2),
                    seats = listOf(SeatInfo(Seat(0), "Alice", isBot = false, ready = true, connected = true)),
                    creatorSeat = Seat(0),
                    yourSeat = Seat(0),
                    phase = RoomPhase.LOBBY,
                ),
            ),
        )
        assertEquals(
            """{"type":"lobby.disbanded","reason":"idleTimeout"}""",
            WireJson.encodeToString<ServerMessage>(LobbyDisbanded(DisbandReason.IDLE_TIMEOUT)),
        )
        assertEquals(
            """{"type":"emote","seat":1,"emote":"oops"}""",
            WireJson.encodeToString<ServerMessage>(EmoteReceived(Seat(1), Emote.OOPS)),
        )
    }

    @Test
    fun `the whole player view is pinned, field for field`() {
        // The largest and most fragile shape on the wire: every field of PlayerView, in order, as an
        // old client expects to read it. Its enums (phase/rank/suit/trump) have no UNKNOWN sink, which
        // is why adding a value to any of them is a PROTOCOL_VERSION bump, not an additive change.
        assertEquals(
            """{"type":"game.view","stateVersion":7,"view":{"seat":0,"phase":"BIDDING",""" +
                """"playerCount":2,"teamCount":2,"handNumber":1,"hand":[""" +
                """{"type":"io.github.rotundtapir.cardkit.core.SuitedCard","rank":"ACE","suit":"SPADES"},""" +
                """{"type":"io.github.rotundtapir.cardkit.core.SuitedCard","rank":"KING","suit":"HEARTS"}],""" +
                """"handSizes":{"0":10,"1":10},"dealer":1,"scores":{"0":0,"1":0},"toAct":0,""" +
                """"biddingHistory":[],"highBid":null,"highBidder":null,""" +
                """"legalBids":[{"type":"pass"},{"type":"named","level":6,"trump":"SPADES"}],""" +
                """"contract":null,"trump":null,"leader":null,"currentTrick":[],"ledSuit":null,""" +
                """"lastTrick":null,"tricksWon":{},"trickNumber":0,"legalPlays":[],"mustDiscard":0,""" +
                """"exposedDeclarerHand":null,"activeSeats":[0,1],"lastHandResult":null,"winner":null},""" +
                """"turnRemainingMillis":30000}""",
            WireJson.encodeToString<ServerMessage>(ViewUpdate(7, sampleView(), turnRemainingMillis = 30_000)),
        )
        // The countdown is absent, not zero, when it is nobody's turn.
        assertFalse(
            WireJson.encodeToString<ServerMessage>(ViewUpdate(8, sampleView())).contains("turnRemainingMillis"),
            "an absent turn timer must be omitted rather than sent as a value",
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
