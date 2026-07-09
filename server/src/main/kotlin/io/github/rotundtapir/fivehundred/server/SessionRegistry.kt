// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.cardkit.core.Seat
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Opaque session tokens issued at [Hello] and their current room+seat binding. A reconnecting client
 * re-presents its token to reclaim its seat. Tokens are unguessable (16 random bytes) so they double
 * as bearer credentials for a seat — no accounts, no passwords.
 */
class SessionRegistry(private val random: SecureRandom = SecureRandom()) {

    /** A token's current placement. Both null until the client sits in a room. */
    class Binding {
        @Volatile var gameId: String? = null
        @Volatile var seat: Seat? = null
    }

    private val bindings = ConcurrentHashMap<String, Binding>()

    /** Mint a fresh, unbound token. */
    fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val token = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        bindings[token] = Binding()
        return token
    }

    /** Record where [token]'s owner is now seated. */
    fun bind(token: String, gameId: String, seat: Seat) {
        bindings.getOrPut(token) { Binding() }.apply {
            this.gameId = gameId
            this.seat = seat
        }
    }

    /** The current binding for [token], or null if the token is unknown. */
    fun lookup(token: String): Binding? = bindings[token]

    /** Forget [token] (e.g. its room disbanded or it left cleanly). */
    fun clear(token: String) {
        bindings.remove(token)
    }

    private companion object {
        const val TOKEN_BYTES = 16
    }
}
