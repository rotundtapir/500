// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import org.slf4j.LoggerFactory

/**
 * Structured, single-line abuse logging — the fail2ban contract. Each line is
 * `ABUSE event=<kind> ip=<client-ip> detail=<...>` on the `abuse` logger; the shipped fail2ban
 * filter regex keys off `ABUSE ... ip=<HOST>`. The client IP is the reverse-proxy-corrected address
 * (X-Forwarded-For when `TRUST_PROXY=true`), so bans land on the real origin, not on Caddy.
 *
 * Per the v1 decision, protocol-strictness violations ([Event.malformed], [Event.oversize_frame])
 * are logged but do not drop the socket yet — we watch the noise floor before enforcing.
 */
class AbuseLog {
    private val logger = LoggerFactory.getLogger("abuse")

    enum class Event {
        CONN_CAP,
        RATE_LIMIT,
        LOBBY_THROTTLE,
        BAD_NAME,
        MALFORMED,
        ILLEGAL_ACTION,
        OVERSIZE_FRAME,
        VERSION_REJECT,
        SERVER_FULL,
    }

    fun log(event: Event, ip: String, detail: String = "") {
        // Lowercased so the log stays `event=conn_cap` (the shipped fail2ban filter expects that).
        logger.warn("ABUSE event={} ip={} detail={}", event.name.lowercase(), ip, detail)
    }
}
