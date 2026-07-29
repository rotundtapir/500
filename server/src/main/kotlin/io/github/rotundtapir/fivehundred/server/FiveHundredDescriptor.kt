// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.cardkit.core.GameRules
import io.github.rotundtapir.cardkit.core.Strategy
import io.github.rotundtapir.cardkit.net.CreateLobbyRequest
import io.github.rotundtapir.cardkit.net.GameOver
import io.github.rotundtapir.cardkit.server.GameDescriptor
import io.github.rotundtapir.fivehundred.ai.FiveHundredBot
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.GameState
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.net.CreateLobby
import io.github.rotundtapir.fivehundred.net.LobbyConfig
import io.github.rotundtapir.fivehundred.net.PROTOCOL_VERSION
import io.github.rotundtapir.fivehundred.net.WireJson

/**
 * Everything `cardkit-server` needs to host 500 — the whole of what makes that generic server *this*
 * game. The room actor, seat hosting, lobbies, reconnect, snapshots and anti-abuse all live in
 * cardkit-server and are unchanged by this being 500 rather than any other cardkit game.
 */
object FiveHundredDescriptor : GameDescriptor<GameState, Action, PlayerView, LobbyConfig> {

    override val gameName: String = "500"
    override val metricsPrefix: String = "fivehundred"
    override val protocolVersion: Int = PROTOCOL_VERSION
    override val wireJson = WireJson
    override val stateSerializer = GameState.serializer()
    override val configSerializer = LobbyConfig.serializer()

    override fun bot(config: LobbyConfig): Strategy<PlayerView, Action> = FiveHundredBot()

    override fun rulesFor(config: LobbyConfig): GameRules<GameState, Action, PlayerView> = FiveHundredRules(
        playerCount = config.playerCount,
        teamCount = config.teamCount,
        misereEnabled = config.misereEnabled,
        noTrumpsEnabled = config.noTrumpsEnabled,
    )

    override fun newGame(config: LobbyConfig, seed: Long): GameState =
        FiveHundredRules(
            playerCount = config.playerCount,
            teamCount = config.teamCount,
            misereEnabled = config.misereEnabled,
            noTrumpsEnabled = config.noTrumpsEnabled,
        ).newGame(seed)

    override fun gameOver(state: GameState): GameOver = GameOver(state.winner ?: -1, state.scores)

    override fun botRestoreSeed(state: GameState): Long = state.rngSeed

    override fun configFrom(request: CreateLobbyRequest): LobbyConfig? {
        val create = request as? CreateLobby ?: return null
        if (!supportedTable(create.playerCount, create.teamCount)) return null
        return LobbyConfig(
            playerCount = create.playerCount,
            teamCount = create.teamCount,
            misereEnabled = create.misereEnabled,
            noTrumpsEnabled = create.noTrumpsEnabled,
            turnTimeoutSeconds = create.turnTimeoutSeconds,
            idleDisbandMinutes = create.idleDisbandMinutes,
        )
    }

    override fun playerCount(config: LobbyConfig): Int = config.playerCount
    override fun turnTimeoutSeconds(config: LobbyConfig): Int = config.turnTimeoutSeconds
    override fun idleDisbandMinutes(config: LobbyConfig): Int = config.idleDisbandMinutes

    override fun withTimeouts(
        config: LobbyConfig,
        turnTimeoutSeconds: Int?,
        idleDisbandMinutes: Int?,
    ): LobbyConfig = config.copy(
        turnTimeoutSeconds = turnTimeoutSeconds ?: config.turnTimeoutSeconds,
        idleDisbandMinutes = idleDisbandMinutes ?: config.idleDisbandMinutes,
    )

    /**
     * The table shapes 500 actually deals: 2, 4 or 6 players in two teams, plus the six-handed
     * three-team variant. Anything else is refused before a room is created.
     */
    fun supportedTable(playerCount: Int, teamCount: Int): Boolean =
        playerCount in setOf(2, 4, 6) && (teamCount == 2 || (teamCount == 3 && playerCount == 6))
}
