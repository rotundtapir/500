// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.server

import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.GameState
import io.github.rotundtapir.fivehundred.engine.PlayerView
import io.github.rotundtapir.fivehundred.net.LobbyConfig

/**
 * `cardkit-server`'s types, bound to 500's four. They are aliased in this package rather than
 * imported at each site so that `GameServer`, `Room` and friends keep reading as they did before the
 * server became generic — the alternative is spelling
 * `GameServer<GameState, Action, PlayerView, LobbyConfig>` at every mention.
 */
typealias GameServer = io.github.rotundtapir.cardkit.server.GameServer<GameState, Action, PlayerView, LobbyConfig>
typealias Room = io.github.rotundtapir.cardkit.server.Room<GameState, Action, PlayerView, LobbyConfig>
typealias RoomRegistry = io.github.rotundtapir.cardkit.server.RoomRegistry<GameState, Action, PlayerView, LobbyConfig>
typealias RoomCommand = io.github.rotundtapir.cardkit.server.RoomCommand<GameState, Action>
typealias SeatHost = io.github.rotundtapir.cardkit.server.SeatHost<PlayerView, Action>
typealias RoomSnapshot = io.github.rotundtapir.cardkit.server.RoomSnapshot<GameState, LobbyConfig>
typealias RoomPersistence = io.github.rotundtapir.cardkit.server.RoomPersistence<GameState, LobbyConfig>
typealias FileRoomPersistence = io.github.rotundtapir.cardkit.server.FileRoomPersistence<GameState, LobbyConfig>

/** A seat's row in a snapshot. Aliased because a typealias cannot reach a nested classifier. */
typealias SeatSnapshot = io.github.rotundtapir.cardkit.server.RoomSnapshot.SeatSnapshot

/** The outcome of a successful create, bound to 500's types. */
typealias RoomCreated =
    io.github.rotundtapir.cardkit.server.RoomRegistry.CreateResult.Created<GameState, Action, PlayerView, LobbyConfig>

/** cardkit's snapshot schema version, re-exported so call sites can use the alias above. */
const val SNAPSHOT_VERSION: Int = io.github.rotundtapir.cardkit.server.RoomSnapshot.CURRENT_VERSION

/** Serializer for 500's room snapshots — its state and lobby config, in cardkit's envelope. */
val snapshotSerializer: kotlinx.serialization.KSerializer<RoomSnapshot> =
    io.github.rotundtapir.cardkit.server.RoomSnapshot.serializer(
        GameState.serializer(),
        LobbyConfig.serializer(),
    )

/** File-backed snapshot storage for 500, with its serializer already supplied. */
fun fileRoomPersistence(
    dir: java.nio.file.Path,
    scope: kotlinx.coroutines.CoroutineScope,
): FileRoomPersistence = FileRoomPersistence(dir, scope, snapshotSerializer)
