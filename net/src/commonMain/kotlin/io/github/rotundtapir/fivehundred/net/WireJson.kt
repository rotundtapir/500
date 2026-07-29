// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.net

import io.github.rotundtapir.cardkit.net.gameWireModule
import io.github.rotundtapir.cardkit.net.wireJson
import io.github.rotundtapir.fivehundred.engine.Action
import io.github.rotundtapir.fivehundred.engine.PlayerView

/**
 * The single JSON configuration used on both ends of 500's wire, kept identical client- and
 * server-side so a frame encoded by one decodes on the other.
 *
 * The configuration itself — unknown keys ignored, unknown enums coerced to their `UNKNOWN` member,
 * fields at their default omitted, `"type"` pinned as the discriminator — lives in `cardkit-net`'s
 * [wireJson]. What 500 adds is the registration of its own payload types. Registering exactly one
 * action/view/config instantiation is what makes this `Json` speak 500 and nothing else.
 */
val WireJson = wireJson(
    gameWireModule(
        actionSerializer = Action.serializer(),
        viewSerializer = PlayerView.serializer(),
        configSerializer = LobbyConfig.serializer(),
        createLobbyClass = CreateLobby::class,
        createLobbySerializer = CreateLobby.serializer(),
    ),
)
