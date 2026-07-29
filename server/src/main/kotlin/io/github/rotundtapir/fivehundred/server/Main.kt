// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.cardkit.server.GameServer
import io.github.rotundtapir.cardkit.server.ServerConfig
import io.github.rotundtapir.cardkit.server.gameServerModule
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("main")

/**
 * 500's server binary: compose `cardkit-server` with [FiveHundredDescriptor] and listen. Everything
 * else — rooms, lobbies, seat hosting, reconnect, snapshots, anti-abuse, `/health`, `/metrics`,
 * `/admin/drain` — is generic and lives in cardkit-server.
 */
fun main() {
    val config = ServerConfig.fromEnv(defaults = FIVE_HUNDRED_DEFAULTS)
    val scope = CoroutineScope(SupervisorJob())
    val server = GameServer(config, scope, FiveHundredDescriptor)
    server.restoreRooms() // before the listener: a reconnect must find its restored room
    server.startMaintenance()
    log.info(
        "Starting {} server on port {} (devMode={}, dataDir={})",
        FiveHundredDescriptor.gameName,
        config.port,
        config.devMode,
        config.dataDir ?: "-",
    )
    embeddedServer(CIO, port = config.port) {
        gameServerModule(server, config)
    }.start(wait = true)
}

/**
 * 500's own identity, which the generic config knows nothing about. Every field stays overridable by
 * an environment variable, so the container remains configured entirely by env vars.
 */
internal val FIVE_HUNDRED_DEFAULTS = ServerConfig(
    allowedOrigins = listOf("https://rotundtapir.github.io"),
    minAppVersion = "0.3.0",
    serverVersion = "0.3.3",
)
