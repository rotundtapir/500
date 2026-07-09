// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.fivehundred.net.ErrorCode
import java.util.concurrent.atomic.AtomicLong

/**
 * Hand-rolled counters rendered as Prometheus text exposition. Deliberately dependency-free (no
 * Micrometer) — at this scale a monitoring stack would consume more of the 1 GB box than it is
 * worth. Read ad-hoc over SSH via `docker compose exec`; `/metrics` is blocked at the proxy.
 */
class Metrics {
    private val connectionsTotal = AtomicLong()
    private val connectionsActive = AtomicLong()
    private val gamesStarted = AtomicLong()
    private val gamesCompleted = AtomicLong()
    private val messagesTotal = AtomicLong()
    private val rejections = ErrorCode.entries.associateWith { AtomicLong() }

    fun connectionOpened() {
        connectionsTotal.incrementAndGet()
        connectionsActive.incrementAndGet()
    }

    fun connectionClosed() {
        connectionsActive.decrementAndGet()
    }

    fun gameStarted() {
        gamesStarted.incrementAndGet()
    }

    fun gameCompleted() {
        gamesCompleted.incrementAndGet()
    }

    fun messageReceived() {
        messagesTotal.incrementAndGet()
    }

    fun rejected(code: ErrorCode) {
        rejections.getValue(code).incrementAndGet()
    }

    /** Render the current values in Prometheus text format. [roomsActive]/[draining] are pulled live. */
    fun render(roomsActive: Int, draining: Boolean): String = buildString {
        fun counter(name: String, help: String, value: Long) {
            append("# HELP ").append(name).append(' ').append(help).append('\n')
            append("# TYPE ").append(name).append(" counter\n")
            append(name).append(' ').append(value).append('\n')
        }
        fun gauge(name: String, help: String, value: Long) {
            append("# HELP ").append(name).append(' ').append(help).append('\n')
            append("# TYPE ").append(name).append(" gauge\n")
            append(name).append(' ').append(value).append('\n')
        }
        counter("fivehundred_connections_total", "WebSocket connections opened", connectionsTotal.get())
        gauge("fivehundred_connections_active", "Currently open connections", connectionsActive.get())
        gauge("fivehundred_rooms_active", "Currently live rooms", roomsActive.toLong())
        counter("fivehundred_games_started_total", "Games started", gamesStarted.get())
        counter("fivehundred_games_completed_total", "Games completed", gamesCompleted.get())
        counter("fivehundred_messages_total", "Client messages received", messagesTotal.get())
        gauge("fivehundred_draining", "1 when the server is draining for restart", if (draining) 1 else 0)
        append("# HELP fivehundred_rejections_total Rejected requests by reason\n")
        append("# TYPE fivehundred_rejections_total counter\n")
        for ((code, count) in rejections) {
            append("fivehundred_rejections_total{reason=\"")
                .append(code.name.lowercase()).append("\"} ").append(count.get()).append('\n')
        }
    }
}
