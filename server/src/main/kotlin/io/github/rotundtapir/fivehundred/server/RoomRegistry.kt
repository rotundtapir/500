// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.fivehundred.net.LobbyConfig
import kotlinx.coroutines.CoroutineScope
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The set of live rooms, indexed both by 4-character join code and by full game id. Also owns the
 * drain flag: while draining, no new lobbies may be created (so active games can finish before a
 * deploy/reboot restarts the process). All state is in-memory — a crashed/restarted server loses
 * every game, which is acceptable (a lost hand, not lost data) and hugely simplifies operations.
 */
class RoomRegistry(
    private val config: ServerConfig,
    private val scope: CoroutineScope,
    private val sessionRegistry: SessionRegistry,
    private val metrics: Metrics,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val byCode = ConcurrentHashMap<String, Room>()
    private val byGameId = ConcurrentHashMap<String, Room>()

    @Volatile
    var draining: Boolean = false
        private set

    /** Flip drain mode on/off. In drain mode, [create] refuses new rooms. */
    fun setDraining(value: Boolean) {
        draining = value
    }

    fun roomCount(): Int = byCode.size

    /** Rooms with a game currently in progress — the deploy drain waits for this to reach zero. */
    fun activeGames(): Int = byCode.values.count { it.isPlaying() }

    /** Find a room by its (case-insensitive) join code. */
    fun find(code: String): Room? = byCode[code.trim().uppercase()]

    fun byGameId(gameId: String): Room? = byGameId[gameId]

    fun all(): Collection<Room> = byCode.values

    /** Outcome of a create attempt: a [Room], or why it was refused. */
    sealed interface CreateResult {
        data class Created(val room: Room) : CreateResult
        data object Draining : CreateResult
        data object ServerFull : CreateResult
    }

    fun create(creatorToken: String, lobbyConfig: LobbyConfig, requestedSeed: Long?): CreateResult {
        if (draining) return CreateResult.Draining
        if (byCode.size >= config.maxRooms) return CreateResult.ServerFull
        val gameId = UUID.randomUUID().toString()
        val code = allocateCode() ?: return CreateResult.ServerFull
        val room = Room(
            gameId = gameId,
            joinCode = code,
            creatorToken = creatorToken,
            initialConfig = lobbyConfig,
            requestedSeed = requestedSeed,
            scope = scope,
            config = config,
            sessionRegistry = sessionRegistry,
            metrics = metrics,
            nowMillis = nowMillis,
            onClosed = ::remove,
        )
        byCode[code] = room
        byGameId[gameId] = room
        room.start()
        return CreateResult.Created(room)
    }

    /** A 4-hex-char prefix that is free. Retries on the rare collision; null if it can't find one. */
    private fun allocateCode(): String? {
        repeat(MAX_CODE_ATTEMPTS) {
            val code = UUID.randomUUID().toString().take(CODE_LENGTH).uppercase()
            if (!byCode.containsKey(code)) return code
        }
        return null
    }

    private fun remove(room: Room) {
        byCode.remove(room.joinCode)
        byGameId.remove(room.gameId)
    }

    private companion object {
        const val CODE_LENGTH = 4
        const val MAX_CODE_ATTEMPTS = 100
    }
}
