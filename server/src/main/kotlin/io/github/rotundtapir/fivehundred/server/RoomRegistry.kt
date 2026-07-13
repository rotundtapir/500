// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.fivehundred.net.LobbyConfig
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

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
    private val abuseLog: AbuseLog,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger("room")
    private val byCode = ConcurrentHashMap<String, Room>()
    private val byGameId = ConcurrentHashMap<String, Room>()
    // Codes are short lookup keys, not secrets — scan resistance comes from the alphabet size and the
    // CODE_SCAN abuse log, not unpredictability — so a plain (non-blocking) RNG is the right choice.
    private val codeRandom = Random.Default

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
    fun find(code: String): Room? = byCode[normalizeCode(code)]

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
        val room = Room(
            gameId = gameId,
            joinCode = "", // set once we successfully claim a code below
            creatorToken = creatorToken,
            initialConfig = lobbyConfig,
            requestedSeed = requestedSeed,
            scope = scope,
            config = config,
            sessionRegistry = sessionRegistry,
            metrics = metrics,
            abuseLog = abuseLog,
            nowMillis = nowMillis,
            onClosed = ::remove,
        )
        if (!claimCode(room)) return CreateResult.ServerFull
        byGameId[gameId] = room
        room.start()
        logger.info(
            "lobby created code={} game={} players={} teams={}",
            room.joinCode,
            gameId,
            lobbyConfig.playerCount,
            lobbyConfig.teamCount,
        )
        return CreateResult.Created(room)
    }

    /**
     * Atomically claim a free code for [room] via [ConcurrentHashMap.putIfAbsent] (so two concurrent
     * creates can't collide on the same code), setting [Room.joinCode]. False if none is free.
     */
    private fun claimCode(room: Room): Boolean {
        repeat(MAX_CODE_ATTEMPTS) {
            val code = randomCode()
            if (byCode.putIfAbsent(code, room) == null) {
                room.joinCode = code
                return true
            }
        }
        return false
    }

    private fun randomCode(): String =
        buildString(CODE_LENGTH) { repeat(CODE_LENGTH) { append(CODE_ALPHABET[codeRandom.nextInt(CODE_ALPHABET.length)]) } }

    private fun remove(room: Room) {
        byCode.remove(room.joinCode)
        byGameId.remove(room.gameId)
    }

    private companion object {
        const val CODE_LENGTH = 4
        const val MAX_CODE_ATTEMPTS = 200

        // Uppercase alphanumeric (codes are always uppercase) minus the glyphs that are ambiguous
        // *in uppercase*: 0/O and 1/I. (L is kept — uppercase L is unmistakable; only lowercase "l"
        // collides with 1/I.) 32 symbols ⇒ 32^4 ≈ 1.05M codes, ~16× the old 16^4 hex space; combined
        // with CODE_SCAN abuse logging, scanning is impractical.
        const val CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

        /** Normalise a user-typed code: trim, uppercase (the alphabet has no ambiguous glyphs to fold). */
        fun normalizeCode(code: String): String = code.trim().uppercase()
    }
}
