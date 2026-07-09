// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.GameState
import io.github.rotundtapir.fivehundred.net.DisbandReason
import io.github.rotundtapir.fivehundred.net.Emote

/**
 * Everything that mutates a [Room] arrives as one of these on the room's single command channel, so
 * a lone consumer coroutine serialises all state changes — no locks, no races. Client requests, the
 * driver's state callbacks, disconnects, and timers are all commands.
 */
sealed interface RoomCommand {
    /** A client requests to sit down (join or create). The creator is identified by session token. */
    data class Join(val connection: PlayerConnection, val displayName: String) : RoomCommand

    data class Reconnect(val connection: PlayerConnection, val seat: Seat) : RoomCommand
    data class SetName(val connection: PlayerConnection, val displayName: String) : RoomCommand
    data class PickSeat(val connection: PlayerConnection, val seat: Seat) : RoomCommand
    data class SetReady(val connection: PlayerConnection, val ready: Boolean) : RoomCommand
    data class Configure(
        val connection: PlayerConnection,
        val turnTimeoutSeconds: Int?,
        val idleDisbandMinutes: Int?,
    ) : RoomCommand

    data class Start(val connection: PlayerConnection) : RoomCommand
    data class Submit(val connection: PlayerConnection, val stateVersion: Int, val action: Action) :
        RoomCommand

    data class SendEmote(val connection: PlayerConnection, val emote: Emote) : RoomCommand
    data class Leave(val connection: PlayerConnection) : RoomCommand
    data class Disband(val connection: PlayerConnection) : RoomCommand
    data class Rematch(val connection: PlayerConnection) : RoomCommand
    data class Disconnected(val connection: PlayerConnection) : RoomCommand

    /** Fired by the [GameDriver] after every applied action (and the initial state). */
    data class StateProduced(val state: GameState) : RoomCommand

    /** Fired when the driver reaches a terminal state. */
    data class GameFinished(val state: GameState) : RoomCommand

    /** A seat's human ran out the turn clock and was bot-substituted. */
    data class SeatTimedOut(val seat: Seat) : RoomCommand

    /** Server-initiated teardown (graceful shutdown), bypassing the creator check. */
    data class ForceDisband(val reason: DisbandReason) : RoomCommand

    /** Periodic idle-disband check. */
    data object IdleCheck : RoomCommand
}
