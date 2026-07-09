// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.net

/**
 * Display-name validation, shared by client and server so the two agree exactly. The client uses it
 * for instant feedback on the entry screen; the server always re-validates (a client can lie).
 *
 * The rules are deliberately conservative: names are the only user-generated content in v1, so this
 * is the whole "inappropriate content" surface. Keep the profanity list small and obvious — it is a
 * speed bump, not a moderation system.
 */
object Names {
    const val MIN_LENGTH: Int = 2
    const val MAX_LENGTH: Int = 20

    /** The suffix reserved for bot seats. A human may not end their name with it (anti-impersonation). */
    const val BOT_SUFFIX: String = "(bot)"

    /** Names that would let a player impersonate the system. */
    private val RESERVED = setOf("server", "admin", "system", "moderator", "host", "bot")

    /** A minimal, obvious block list. Substring match on the normalized (lower-cased) name. */
    private val PROFANITY = setOf("fuck", "shit", "cunt", "nigger", "faggot", "bitch", "rape")

    /** Letters (incl. common Latin diacritics), digits, space, and a few name punctuation marks. */
    private fun isAllowed(c: Char): Boolean =
        c.isLetterOrDigit() || c == ' ' || c == '-' || c == '\'' || c == '_' || c == '.'

    /** Why a name was rejected. */
    enum class Rejection { TOO_SHORT, TOO_LONG, ILLEGAL_CHARS, BOT_SUFFIX, RESERVED, PROFANITY }

    /** The outcome of validating a raw name: either the cleaned, accepted form or a [Rejection]. */
    sealed interface Result {
        data class Ok(val name: String) : Result
        data class Rejected(val reason: Rejection) : Result
    }

    /** Collapse internal whitespace runs to a single space and trim the ends. */
    fun normalize(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    /**
     * Validate [raw]. On success returns [Result.Ok] with the normalized name to store/broadcast.
     * The [BOT_SUFFIX] check is case- and trailing-space-insensitive.
     */
    fun validate(raw: String): Result {
        val name = normalize(raw)
        val lower = name.lowercase()
        return when {
            name.length < MIN_LENGTH -> Result.Rejected(Rejection.TOO_SHORT)
            name.length > MAX_LENGTH -> Result.Rejected(Rejection.TOO_LONG)
            // Checked before the charset rule so a deliberate "(bot)" impersonation attempt is
            // reported as such, rather than as an incidental illegal-character rejection (the
            // parentheses are not in the allowed set either).
            lower.endsWith(BOT_SUFFIX) -> Result.Rejected(Rejection.BOT_SUFFIX)
            !name.all(::isAllowed) -> Result.Rejected(Rejection.ILLEGAL_CHARS)
            lower in RESERVED -> Result.Rejected(Rejection.RESERVED)
            PROFANITY.any { lower.contains(it) } -> Result.Rejected(Rejection.PROFANITY)
            else -> Result.Ok(name)
        }
    }

    /** Convenience for UI: true iff [raw] validates. */
    fun isValid(raw: String): Boolean = validate(raw) is Result.Ok

    /** Render a bot's table label, e.g. `"Daisy (bot)"`. */
    fun botLabel(baseName: String): String = "$baseName $BOT_SUFFIX"
}
