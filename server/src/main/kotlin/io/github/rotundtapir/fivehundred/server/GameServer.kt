// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.fivehundred.net.ClientMessage
import io.github.rotundtapir.fivehundred.net.ConfigureLobby
import io.github.rotundtapir.fivehundred.net.CreateLobby
import io.github.rotundtapir.fivehundred.net.DisbandLobby
import io.github.rotundtapir.fivehundred.net.ErrorCode
import io.github.rotundtapir.fivehundred.net.ErrorMessage
import io.github.rotundtapir.fivehundred.net.Hello
import io.github.rotundtapir.fivehundred.net.JoinLobby
import io.github.rotundtapir.fivehundred.net.LeaveLobby
import io.github.rotundtapir.fivehundred.net.LobbyConfig
import io.github.rotundtapir.fivehundred.net.Names
import io.github.rotundtapir.fivehundred.net.PROTOCOL_VERSION
import io.github.rotundtapir.fivehundred.net.PickSeat
import io.github.rotundtapir.fivehundred.net.RequestRematch
import io.github.rotundtapir.fivehundred.net.SendEmote
import io.github.rotundtapir.fivehundred.net.ServerMessage
import io.github.rotundtapir.fivehundred.net.SetName
import io.github.rotundtapir.fivehundred.net.SetReady
import io.github.rotundtapir.fivehundred.net.StartGame
import io.github.rotundtapir.fivehundred.net.SubmitAction
import io.github.rotundtapir.fivehundred.net.UpdateRequired
import io.github.rotundtapir.fivehundred.net.Welcome
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Transport-agnostic orchestration: owns the registries and shared limiters, decides the fate of a
 * [Hello], and routes every subsequent [ClientMessage] to the right [Room]. The Ktor layer ([Main])
 * only owns socket I/O and hands decoded messages here, which keeps the business logic unit-testable
 * without a real WebSocket.
 */
class GameServer(
    val config: ServerConfig,
    scope: CoroutineScope,
    val metrics: Metrics = Metrics(),
    private val abuseLog: AbuseLog = AbuseLog(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    val sessionRegistry = SessionRegistry()
    val rooms = RoomRegistry(config, scope, sessionRegistry, metrics, nowMillis)

    private val connectionIds = AtomicLong()
    private val connectionsPerIp = ConcurrentHashMap<String, AtomicInteger>()
    private val lobbyThrottle =
        SlidingWindowCounter(LOBBY_WINDOW_MILLIS, config.lobbiesPerIpPer10Min, nowMillis)

    fun nextConnectionId(): Long = connectionIds.incrementAndGet()

    /** Enforce the per-IP connection cap. Returns false (and logs) when [ip] is over the limit. */
    fun tryOpenConnection(ip: String): Boolean {
        if (config.devMode) return true
        val count = connectionsPerIp.getOrPut(ip) { AtomicInteger() }
        return if (count.incrementAndGet() <= config.maxConnectionsPerIp) {
            true
        } else {
            count.decrementAndGet()
            abuseLog.log(AbuseLog.Event.CONN_CAP, ip, "over ${config.maxConnectionsPerIp}")
            false
        }
    }

    fun closeConnection(ip: String) {
        connectionsPerIp[ip]?.let { if (it.decrementAndGet() <= 0) connectionsPerIp.remove(ip, it) }
    }

    /** The verdict on a client's opening [Hello]. */
    sealed interface HelloResult {
        data class Accepted(
            val token: String,
            val welcome: Welcome,
            val resumeRoom: Room?,
            val resumeSeat: Seat?,
        ) : HelloResult

        data class Rejected(val response: UpdateRequired) : HelloResult
    }

    fun processHello(hello: Hello, ip: String): HelloResult {
        if (hello.protocolVersion != PROTOCOL_VERSION ||
            !Versions.isAtLeast(hello.appVersion, config.minAppVersion)
        ) {
            abuseLog.log(AbuseLog.Event.VERSION_REJECT, ip, "app=${hello.appVersion} proto=${hello.protocolVersion}")
            metrics.rejected(ErrorCode.VERSION_UNSUPPORTED)
            return HelloResult.Rejected(
                UpdateRequired(config.minAppVersion, "Please update the app to play online."),
            )
        }
        val existing = hello.sessionToken?.let { sessionRegistry.lookup(it) }
        val token = if (existing != null) hello.sessionToken!! else sessionRegistry.newToken()
        val resumeRoom = existing?.gameId?.let { rooms.byGameId(it) }
        val resumeSeat = existing?.seat
        return HelloResult.Accepted(
            token = token,
            welcome = Welcome(token, config.serverVersion, resumeRoom?.resumedState()),
            resumeRoom = resumeRoom,
            resumeSeat = resumeSeat,
        )
    }

    /** Route one already-decoded, already-rate-limited client message. */
    fun route(connection: PlayerConnection, message: ClientMessage) {
        when (message) {
            is Hello -> Unit // handled once at connect
            is CreateLobby -> handleCreate(connection, message)
            is JoinLobby -> handleJoinLobby(connection, message)
            is SetName -> forward(connection) { RoomCommand.SetName(connection, message.displayName) }
            is PickSeat -> forward(connection) { RoomCommand.PickSeat(connection, message.seat) }
            is SetReady -> forward(connection) { RoomCommand.SetReady(connection, message.ready) }
            is ConfigureLobby -> forward(connection) {
                RoomCommand.Configure(connection, message.turnTimeoutSeconds, message.idleDisbandMinutes)
            }
            StartGame -> forward(connection) { RoomCommand.Start(connection) }
            LeaveLobby -> forward(connection) { RoomCommand.Leave(connection) }
            DisbandLobby -> forward(connection) { RoomCommand.Disband(connection) }
            RequestRematch -> forward(connection) { RoomCommand.Rematch(connection) }
            is SubmitAction -> forward(connection) {
                RoomCommand.Submit(connection, message.stateVersion, message.action)
            }
            is SendEmote -> forward(connection) { RoomCommand.SendEmote(connection, message.emote) }
        }
    }

    /** Notify the current room that a socket dropped, so it can bot-substitute or free the seat. */
    fun onDisconnected(connection: PlayerConnection) {
        connection.connected = false
        roomOf(connection)?.submit(RoomCommand.Disconnected(connection))
    }

    private fun handleCreate(connection: PlayerConnection, message: CreateLobby) {
        if (!validName(connection, message.displayName)) return
        if (!validConfig(message.playerCount, message.teamCount)) {
            return deliver(connection, ErrorMessage(ErrorCode.BAD_CONFIG, "Unsupported player/team count"))
        }
        if (!config.devMode && !lobbyThrottle.tryRecord(connection.remoteIp)) {
            abuseLog.log(AbuseLog.Event.LOBBY_THROTTLE, connection.remoteIp)
            return deliver(connection, ErrorMessage(ErrorCode.RATE_LIMITED, "Too many lobbies; slow down"))
        }
        val lobbyConfig = LobbyConfig(
            playerCount = message.playerCount,
            teamCount = message.teamCount,
            misereEnabled = message.misereEnabled,
            noTrumpsEnabled = message.noTrumpsEnabled,
            turnTimeoutSeconds = message.turnTimeoutSeconds,
            idleDisbandMinutes = message.idleDisbandMinutes,
        )
        when (val result = rooms.create(connection.sessionToken, lobbyConfig, message.seed)) {
            is RoomRegistry.CreateResult.Created ->
                result.room.submit(RoomCommand.Join(connection, message.displayName))
            RoomRegistry.CreateResult.Draining ->
                deliver(connection, ErrorMessage(ErrorCode.SERVER_DRAINING, "Server restarting soon; try shortly"))
            RoomRegistry.CreateResult.ServerFull -> {
                abuseLog.log(AbuseLog.Event.SERVER_FULL, connection.remoteIp)
                deliver(connection, ErrorMessage(ErrorCode.SERVER_FULL, "Server at capacity"))
            }
        }
    }

    private fun handleJoinLobby(connection: PlayerConnection, message: JoinLobby) {
        if (!validName(connection, message.displayName)) return
        val room = rooms.find(message.code)
            ?: return deliver(connection, ErrorMessage(ErrorCode.NO_SUCH_LOBBY, "No lobby with that code"))
        room.submit(RoomCommand.Join(connection, message.displayName))
    }

    private inline fun forward(connection: PlayerConnection, command: () -> RoomCommand) {
        val room = roomOf(connection)
            ?: return deliver(connection, ErrorMessage(ErrorCode.NOT_IN_LOBBY, "You are not in a lobby"))
        room.submit(command())
    }

    private fun roomOf(connection: PlayerConnection): Room? =
        connection.roomId?.let { rooms.byGameId(it) }

    private fun validName(connection: PlayerConnection, raw: String): Boolean {
        if (Names.isValid(raw)) return true
        abuseLog.log(AbuseLog.Event.BAD_NAME, connection.remoteIp, raw.take(NAME_LOG_LIMIT))
        deliver(connection, ErrorMessage(ErrorCode.BAD_NAME, "Name not allowed"))
        return false
    }

    private fun validConfig(playerCount: Int, teamCount: Int): Boolean =
        playerCount in setOf(2, 4, 6) && (teamCount == 2 || (teamCount == 3 && playerCount == 6))

    private fun deliver(connection: PlayerConnection, message: ServerMessage) {
        if (!connection.enqueue(message)) connection.requestClose()
    }

    private companion object {
        const val LOBBY_WINDOW_MILLIS = 10L * 60 * 1000
        const val NAME_LOG_LIMIT = 24
    }
}
