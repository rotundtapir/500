// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.engine

import io.github.rotundtapir.cardkit.testing.driveRandomly
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Fuzz sweep: play whole matches with uniformly random legal moves and assert what must hold at
 * every step and at the end. The per-step half comes free from cardkit-testing's [driveRandomly] —
 * it fails if a non-terminal state names no actor, if an actor has no legal action (a silent stall),
 * or if a chosen action isn't in that seat's own `legalActions` — so this doubles as a legality
 * sweep across every table shape and house-rule combination.
 *
 * The existing tests all drive *chosen* lines (a specific auction, a specific trick). This is the
 * complement: it goes where nobody thought to look, which for a pure reducer is where the bugs are.
 *
 * The kitty exchange needs the driver's `construct` hook: "discard any 3 of your 13" is a
 * combinatorial set the rules deliberately don't enumerate, so `legalActions` is empty there while
 * the declarer still has a move to make (see [randomDiscard]).
 */
class RandomPlaySweepTest {

    @Test
    fun `random legal play terminates cleanly at every table shape`() {
        for ((players, teams) in TABLES) {
            for (seed in 0L until SEEDS_PER_TABLE) {
                val rules = FiveHundredRules(playerCount = players, teamCount = teams)
                var steps = 0
                val rng = Random(seed)
                val terminal = driveRandomly(
                    rules = rules,
                    initial = rules.newGame(seed),
                    rng = rng,
                    onState = { steps++ },
                    construct = { view -> randomDiscard(view, rng) },
                )
                val label = "$players players / $teams teams, seed $seed"
                assertTrue(rules.isTerminal(terminal), "$label: driver returned a non-terminal state")
                assertEquals(Phase.COMPLETE, terminal.phase, label)
                assertEquals(null, rules.currentActor(terminal), "$label: a finished match still wants an actor")
                // Semantic rather than a step-count floor. Two attempts at "suspiciously short"
                // both caught legitimate matches instead of bugs: a 2-player hand can score 500
                // outright (~24 states), and a misère hand seats the declarer's teammates out, so
                // a 4-player hand can be 30 plays rather than 40. What actually must hold is that
                // a hand was scored; `steps` stays in the messages as diagnostics only.
                assertTrue(terminal.handResults.isNotEmpty(), "$label: complete without scoring a hand ($steps states)")

                // Somebody won, and by the rules: a team at or past the target, and no other team
                // higher (a tie-break bug would show up here rather than in a chosen line).
                val winner = assertNotNull(terminal.winner, "$label: complete but nobody won")
                val scores = terminal.scores
                assertTrue(
                    scores.getValue(winner) >= WIN_TARGET || scores.values.any { it <= LOSE_TARGET },
                    "$label: winner $winner on ${scores[winner]} with scores $scores",
                )
                assertTrue(
                    scores.all { (team, score) -> team == winner || score <= scores.getValue(winner) },
                    "$label: winner $winner does not hold the highest score — $scores",
                )
            }
        }
    }

    @Test
    fun `random play is reproducible from its seed`() {
        // The whole suite (and every bug report) leans on determinism, so pin it against the fuzz
        // driver too: same seed in, byte-identical trajectory out.
        val rules = FiveHundredRules()
        fun run(): List<Int> = buildList {
            val rng = Random(4242L)
            driveRandomly(
                rules = rules,
                initial = rules.newGame(seed = 4242L),
                rng = rng,
                onState = { state -> add(state.scores.values.sum()) },
                construct = { view -> randomDiscard(view, rng) },
            )
        }
        assertEquals(run(), run(), "the same seed must replay the same match")
    }

    @Test
    fun `house-rule combinations are all playable`() {
        // Disabling a denomination narrows the bid ladder; the auction must still always resolve
        // (all-pass is legal), and no combination may leave a seat with nothing legal to do.
        for (misere in listOf(true, false)) {
            for (noTrumps in listOf(true, false)) {
                val rules = FiveHundredRules(misereEnabled = misere, noTrumpsEnabled = noTrumps)
                val rng = Random(11L)
                val terminal = driveRandomly(
                    rules = rules,
                    initial = rules.newGame(seed = 11L),
                    rng = rng,
                    construct = { view -> randomDiscard(view, rng) },
                )
                assertEquals(
                    Phase.COMPLETE,
                    terminal.phase,
                    "misere=$misere noTrumps=$noTrumps did not reach a complete match",
                )
            }
        }
    }

    /**
     * The kitty phase's constructed action: bin [PlayerView.mustDiscard] cards at random. Built from
     * the seat's own view, so the sweep never sees more than a real player would.
     */
    private fun randomDiscard(view: PlayerView, rng: Random): Action? =
        if (view.mustDiscard > 0) Action.ExchangeKitty(view.hand.shuffled(rng).take(view.mustDiscard)) else null

    private companion object {
        /** Every supported table: 2 and 4 players in two teams, 6 players as 2×3 and as 3×2. */
        val TABLES = listOf(2 to 2, 4 to 2, 6 to 2, 6 to 3)

        /** Seeds per table. Kept modest: a full match is thousands of steps, and CI runs this. */
        const val SEEDS_PER_TABLE = 12L

        const val WIN_TARGET = 500
        const val LOSE_TARGET = -500
    }
}
