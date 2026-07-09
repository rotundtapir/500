// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.fivehundred.net.Platform
import io.github.rotundtapir.fivehundred.net.ServerMessage
import kotlinx.coroutines.channels.Channel

/**
 * One connected client, as seen by the rooms. Deliberately transport-agnostic (no Ktor types) so a
 * [Room] can be driven by a plain in-memory connection in tests. The real WebSocket handler drains
 * [outbound] to the socket and calls [requestClose] to tear it down.
 *
 * [enqueue] never suspends: a bounded outbound channel means a slow client cannot block the room
 * actor. When the buffer overflows the connection is marked to be closed (slow-consumer eviction).
 */
class PlayerConnection(
    val id: Long,
    val sessionToken: String,
    val remoteIp: String,
    val platform: Platform,
    val appVersion: String,
    /** Called when the room wants this socket torn down (e.g. a zombie kicked on reconnect). */
    val requestClose: () -> Unit,
) {
    /** Bounded so one stalled phone can never wedge the room; overflow ⇒ evict. */
    val outbound: Channel<ServerMessage> = Channel(capacity = OUTBOUND_CAPACITY)

    @Volatile
    var connected: Boolean = true

    /** The room this connection is currently in, and the seat it occupies (both set once seated). */
    @Volatile
    var roomId: String? = null

    @Volatile
    var seat: Seat? = null

    /** Queue [message] for delivery. Returns false if the buffer is full (caller should evict). */
    fun enqueue(message: ServerMessage): Boolean = outbound.trySend(message).isSuccess

    companion object {
        const val OUTBOUND_CAPACITY: Int = 64
    }
}
