// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.fivehundred.net.ClientMessage
import io.github.rotundtapir.fivehundred.net.ErrorCode
import io.github.rotundtapir.fivehundred.net.ErrorMessage
import io.github.rotundtapir.fivehundred.net.Hello
import io.github.rotundtapir.fivehundred.net.ServerMessage
import io.github.rotundtapir.fivehundred.net.WireJson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("main")

/** WebSocket close code for a client that is too old to talk to this server. */
const val CLOSE_UPDATE_REQUIRED: Short = 4426

fun main() {
    val config = ServerConfig.fromEnv()
    val scope = CoroutineScope(SupervisorJob())
    val server = GameServer(config, scope)
    log.info("Starting 500 server on port {} (devMode={})", config.port, config.devMode)
    embeddedServer(CIO, port = config.port) {
        gameServerModule(server, config)
    }.start(wait = true)
}

/**
 * Installs the WebSocket transport and HTTP endpoints for [server]. Split from [main] so tests can
 * host it with `testApplication` and a custom [GameServer]/[ServerConfig].
 */
fun Application.gameServerModule(server: GameServer, config: ServerConfig) {
    install(WebSockets) {
        pingPeriodMillis = PING_PERIOD_SECONDS * 1000
        timeoutMillis = PONG_TIMEOUT_SECONDS * 1000
        maxFrameSize = config.maxFrameBytes
    }
    if (config.trustProxy) install(XForwardedHeaders)

    monitor.subscribe(io.ktor.server.application.ApplicationStopPreparing) {
        server.rooms.all().forEach { it.shutdown() }
    }

    routing {
        get("/health") {
            val body = """{"status":"ok","rooms":${server.rooms.roomCount()},""" +
                """"activeGames":${server.rooms.activeGames()},"draining":${server.rooms.draining}}"""
            call.respondText(body, io.ktor.http.ContentType.Application.Json)
        }
        get("/metrics") {
            call.respondText(server.metrics.render(server.rooms.roomCount(), server.rooms.draining))
        }
        post("/admin/drain") {
            server.rooms.setDraining(true)
            call.respondText("draining")
        }
        post("/admin/undrain") {
            server.rooms.setDraining(false)
            call.respondText("serving")
        }
        webSocket("/ws") {
            handleSocket(server, config)
        }
    }
}

/** The per-connection lifecycle: handshake, then pump frames until the socket closes. */
private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.handleSocket(
    server: GameServer,
    config: ServerConfig,
) {
    val origin = call.request.headers["Origin"]
    if (!config.originAllowed(origin)) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "origin not allowed"))
        return
    }
    val ip = call.request.origin.remoteHost
    if (!server.tryOpenConnection(ip)) {
        close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "too many connections"))
        return
    }
    server.metrics.connectionOpened()
    try {
        val hello = readHello() ?: return
        when (val result = server.processHello(hello, ip)) {
            is GameServer.HelloResult.Rejected -> {
                sendMessage(result.response)
                close(CloseReason(CLOSE_UPDATE_REQUIRED, "update required"))
                return
            }
            is GameServer.HelloResult.Accepted -> runSession(server, config, hello, ip, result)
        }
    } finally {
        server.closeConnection(ip)
        server.metrics.connectionClosed()
    }
}

private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.runSession(
    server: GameServer,
    config: ServerConfig,
    hello: Hello,
    ip: String,
    accepted: GameServer.HelloResult.Accepted,
) {
    val connection = PlayerConnection(
        id = server.nextConnectionId(),
        sessionToken = accepted.token,
        remoteIp = ip,
        platform = hello.platform,
        appVersion = hello.appVersion,
        requestClose = { launch { runCatching { close(CloseReason(CloseReason.Codes.NORMAL, "evicted")) } } },
    )
    sendMessage(accepted.welcome)
    val resumeRoom = accepted.resumeRoom
    val resumeSeat = accepted.resumeSeat
    if (resumeRoom != null && resumeSeat != null) {
        connection.roomId = resumeRoom.gameId
        resumeRoom.submit(RoomCommand.Reconnect(connection, resumeSeat))
    }
    val writer = launch {
        for (message in connection.outbound) sendMessage(message)
    }
    val limiter = RateLimiter(config.messageRatePerSecond, config.messageBurst, System::currentTimeMillis)
    try {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            pumpFrame(server, connection, limiter, frame.readText())
        }
    } finally {
        connection.connected = false
        connection.outbound.close()
        writer.cancel()
        server.onDisconnected(connection)
    }
}

private fun io.ktor.server.websocket.DefaultWebSocketServerSession.pumpFrame(
    server: GameServer,
    connection: PlayerConnection,
    limiter: RateLimiter,
    text: String,
) {
    if (!server.config.devMode && !limiter.tryAcquire()) {
        server.metrics.rejected(ErrorCode.RATE_LIMITED)
        connection.enqueue(ErrorMessage(ErrorCode.RATE_LIMITED, "Slow down"))
        return
    }
    server.metrics.messageReceived()
    val message = runCatching { WireJson.decodeFromString<ClientMessage>(text) }.getOrNull()
    if (message == null) {
        // Log-only for now (per the v1 decision) — malformed frames don't drop the socket yet.
        server.metrics.rejected(ErrorCode.MALFORMED)
        connection.enqueue(ErrorMessage(ErrorCode.MALFORMED, "Unrecognised message"))
        return
    }
    server.route(connection, message)
}

/** Read and decode the opening [Hello], bounded by a short timeout so silent sockets don't linger. */
private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.readHello(): Hello? {
    val text = withTimeoutOrNull(HELLO_TIMEOUT_SECONDS.seconds) {
        (incoming.receive() as? Frame.Text)?.readText()
    } ?: return null
    return runCatching { WireJson.decodeFromString<ClientMessage>(text) }.getOrNull() as? Hello
}

private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.sendMessage(message: ServerMessage) {
    send(Frame.Text(WireJson.encodeToString<ServerMessage>(message)))
}

private const val PING_PERIOD_SECONDS = 20L
private const val PONG_TIMEOUT_SECONDS = 40L
private const val HELLO_TIMEOUT_SECONDS = 15L
