// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

/**
 * All server tunables, read from the environment so the Docker container is configured entirely by
 * env vars (see docker-compose.yml / self-hosting docs). Defaults are safe for the public server on
 * a 1 vCPU / 1 GB VPS.
 */
data class ServerConfig(
    val port: Int = 8080,
    /** Trust `X-Forwarded-*` headers (true only behind the Caddy reverse proxy). */
    val trustProxy: Boolean = false,
    /** Origins allowed to open a WebSocket (CSWSH defence). `*` disables the check (LAN/self-host). */
    val allowedOrigins: List<String> = listOf("https://rotundtapir.github.io"),
    /** Oldest app version the server still accepts; older clients are told to update. */
    val minAppVersion: String = "0.3.0",
    val serverVersion: String = "0.3.0",
    val maxConnectionsPerIp: Int = 8,
    val messageRatePerSecond: Int = 10,
    val messageBurst: Int = 20,
    val lobbiesPerIpPer10Min: Int = 5,
    val maxRooms: Int = 500,
    val maxFrameBytes: Long = 16 * 1024,
    /** Relaxes IP caps and honours a client-supplied game seed. For local dev / e2e only. */
    val devMode: Boolean = false,
    /** Test hook: force the per-turn timeout to this many ms, ignoring the lobby's seconds setting. */
    val turnTimeoutMillisOverride: Long? = null,
    /** Test hook: force the idle-disband timeout to this many ms, ignoring the lobby's minutes setting. */
    val idleDisbandMillisOverride: Long? = null,
) {
    /** True when the Origin check is disabled. */
    val allowAnyOrigin: Boolean get() = allowedOrigins.any { it == "*" }

    /** Whether [origin] (may be null for non-browser clients) is permitted to connect. */
    fun originAllowed(origin: String?): Boolean =
        allowAnyOrigin || origin == null || origin in allowedOrigins

    companion object {
        /** Build a config from a `getenv`-style lookup (injectable so tests don't touch the real env). */
        fun fromEnv(getenv: (String) -> String? = System::getenv): ServerConfig {
            fun int(name: String, default: Int) = getenv(name)?.toIntOrNull() ?: default
            fun long(name: String, default: Long) = getenv(name)?.toLongOrNull() ?: default
            fun bool(name: String, default: Boolean) =
                getenv(name)?.let { it.equals("true", ignoreCase = true) || it == "1" } ?: default
            return ServerConfig(
                port = int("PORT", 8080),
                trustProxy = bool("TRUST_PROXY", false),
                allowedOrigins = getenv("ALLOWED_ORIGINS")
                    ?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
                    ?: listOf("https://rotundtapir.github.io"),
                minAppVersion = getenv("MIN_APP_VERSION") ?: "0.3.0",
                serverVersion = getenv("SERVER_VERSION") ?: "0.3.0",
                maxConnectionsPerIp = int("MAX_CONNECTIONS_PER_IP", 8),
                messageRatePerSecond = int("MSG_RATE_PER_SEC", 10),
                messageBurst = int("MSG_BURST", 20),
                lobbiesPerIpPer10Min = int("LOBBIES_PER_IP_PER_10MIN", 5),
                maxRooms = int("MAX_ROOMS", 500),
                maxFrameBytes = long("MAX_FRAME_BYTES", 16 * 1024),
                devMode = bool("DEV_MODE", false),
            )
        }
    }
}
