// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** The lifecycle of a single connection attempt. Reconnect orchestration lives above this. */
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, CLOSED }

/**
 * The client half of the wire protocol: one WebSocket to the server, decoded messages out, encoded
 * messages in. Deliberately an interface so the UI layer can be tested against a fake without a real
 * socket. Reconnect-with-token and backoff are the caller's job (see the online ViewModel) — this
 * type models exactly one connection.
 */
interface GameClient {
    /** Decoded frames from the server. Hot; collect before/while [run] executes. */
    val incoming: SharedFlow<ServerMessage>

    /** The current connection lifecycle state. */
    val state: StateFlow<ConnectionState>

    /**
     * Connects to [serverUrl] (a `ws://`/`wss://` URL) and runs the receive loop, suspending until
     * the socket closes or the calling coroutine is cancelled. Frames are decoded onto [incoming].
     */
    suspend fun run(serverUrl: String)

    /** Encodes and transmits [message] over the current session. No-op if not connected. */
    suspend fun send(message: ClientMessage)

    /** Closes the current session, if any. */
    suspend fun close()
}

/**
 * The production [GameClient] over Ktor. The [httpClientFactory] is injectable so tests can supply a
 * mock-engine client; production uses the platform default (CIO on JVM/Android, the JS engine on
 * wasmJs).
 */
class KtorGameClient(
    private val httpClientFactory: () -> HttpClient = ::defaultHttpClient,
) : GameClient {

    private val _incoming = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<ServerMessage> = _incoming.asSharedFlow()

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var httpClient: HttpClient? = null
    private var session: DefaultClientWebSocketSession? = null

    override suspend fun run(serverUrl: String) {
        _state.value = ConnectionState.CONNECTING
        val client = httpClientFactory().also { httpClient = it }
        try {
            val ws = client.webSocketSession(urlString = serverUrl)
            session = ws
            _state.value = ConnectionState.CONNECTED
            for (frame in ws.incoming) {
                if (frame is Frame.Text) {
                    decode(frame.readText())?.let { _incoming.emit(it) }
                }
            }
        } finally {
            _state.value = ConnectionState.CLOSED
            session = null
            httpClient = null
            client.close()
        }
    }

    override suspend fun send(message: ClientMessage) {
        session?.send(Frame.Text(WireJson.encodeToString<ClientMessage>(message)))
    }

    override suspend fun close() {
        session?.close()
    }

    /** Decode a text frame, dropping anything unparseable (a malformed frame must not kill the loop). */
    private fun decode(text: String): ServerMessage? =
        runCatching { WireJson.decodeFromString<ServerMessage>(text) }.getOrNull()
}

/** Platform WebSocket-capable [HttpClient]: CIO on JVM/Android, the JS engine on wasmJs. */
expect fun defaultHttpClient(): HttpClient
