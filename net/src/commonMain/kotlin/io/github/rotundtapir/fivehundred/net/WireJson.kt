// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.net

import kotlinx.serialization.json.Json

/**
 * The single JSON configuration used on both ends of the wire. Kept identical client- and
 * server-side so a frame encoded by one decodes on the other.
 *
 *  - [Json.ignoreUnknownKeys]: newer peers may add fields; older peers skip them (forward compat).
 *  - [Json.coerceInputValues]: an unknown enum value coerces to that enum's default member — every
 *    forward-compat enum defaults to its `UNKNOWN` case, so new emotes/error codes never break an
 *    old client (paired with the `= …UNKNOWN` defaults on message fields carrying those enums).
 *  - [Json.encodeDefaults] off: fields equal to their default are omitted, which is exactly what
 *    makes adding an optional field a non-breaking change.
 *  - `classDiscriminator = "type"` is the kotlinx default, pinned here so a library default change
 *    can never silently alter the wire format.
 */
val WireJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = false
    classDiscriminator = "type"
}
