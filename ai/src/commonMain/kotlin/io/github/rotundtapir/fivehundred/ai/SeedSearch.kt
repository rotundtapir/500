// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ai

import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.FiveHundredRules
import io.github.rotundtapir.fivehundred.engine.ScoreSchedule
import io.github.rotundtapir.fivehundred.engine.Trump

/**
 * Finds a seed whose opening deal gives a seat the kind of hand you asked for — the "rigged deck"
 * behind the offline cheats menu, and the same plumbing a hand-specific bug report needs.
 *
 * Lives here rather than in the UI because the hand estimator ([FiveHundredBot.candidateBids] /
 * [FiveHundredBot.estimateTricks]) is `internal` to this module, and because a predicate over deals
 * is worth unit-testing on its own.
 *
 * Rejection sampling: deal from a candidate seed, look at the seat's hand, keep the seed if the
 * predicate holds. Callers on the browser's single thread must not spin — [findSeedFor] is
 * `suspend` and yields between attempts, and it returns null rather than looping forever when the
 * target is rarer than [maxAttempts].
 */
object SeedSearch {

    /** How many candidate seeds a search tries before giving up. */
    const val DEFAULT_MAX_ATTEMPTS = 4_000

    /**
     * The first seed at or after [from] whose deal satisfies [predicate] for [seat], or null after
     * [maxAttempts]. [onProgress] is called every [PROGRESS_EVERY] attempts so a UI can show that
     * the search is alive (and, being a suspend function, this yields so the canvas keeps painting).
     */
    suspend fun findSeedFor(
        from: Long = 0L,
        seat: Seat = Seat(0),
        playerCount: Int = 4,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        onProgress: suspend (attempts: Int) -> Unit = { },
        predicate: (hand: List<Card>) -> Boolean,
    ): Long? {
        val rules = FiveHundredRules(playerCount = playerCount)
        for (attempt in 0 until maxAttempts) {
            val seed = from + attempt
            if (predicate(rules.newGame(seed).hands.getValue(seat))) return seed
            if (attempt % PROGRESS_EVERY == PROGRESS_EVERY - 1) onProgress(attempt + 1)
        }
        return null
    }

    /**
     * "A hand worth bidding at [level] or above in some suit." Expressed as a ladder comparison
     * against [level]♠ (the lowest denomination at that level) rather than as a raw
     * [ScoreSchedule.rank] index: the ladder is deliberately not point-ordered — Misère sits
     * between 8♠ and 8♣ — so a bare index means something non-obvious.
     *
     * Misère hands are deliberately EXCLUDED: a hand that can only bid Misère outranks 8♠ on the
     * ladder while being the opposite of the strong hand this asks for. Use [wantsMisere] for that.
     */
    fun atLeastLevel(level: Int, schedule: ScoreSchedule = ScoreSchedule.Avondale): (List<Card>) -> Boolean {
        val floor = schedule.rank(Bid.Named(level, Trump.SPADES))
        val bot = FiveHundredBot()
        return { hand ->
            bot.candidateBids(hand)
                .filterIsInstance<Bid.Named>()
                .any { schedule.rank(it) >= floor }
        }
    }

    /** "A hand the bot would bid [trump] at [level] or higher on" — the deal you can't wait for. */
    fun atLeastLevelIn(
        level: Int,
        trump: Trump,
        schedule: ScoreSchedule = ScoreSchedule.Avondale,
    ): (List<Card>) -> Boolean {
        val floor = schedule.rank(Bid.Named(level, trump))
        val bot = FiveHundredBot()
        return { hand ->
            bot.candidateBids(hand)
                .filterIsInstance<Bid.Named>()
                .any { it.trump == trump && schedule.rank(it) >= floor }
        }
    }

    /** "A hand worth a Misère" — rare by construction, and tedious to reach by chance. */
    fun wantsMisere(): (List<Card>) -> Boolean {
        val bot = FiveHundredBot()
        return { hand -> bot.candidateBids(hand).any { it == Bid.Misere || it == Bid.OpenMisere } }
    }

    private const val PROGRESS_EVERY = 250
}
