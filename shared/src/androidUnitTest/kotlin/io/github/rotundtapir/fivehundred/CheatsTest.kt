// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred

import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.ui.settings.InMemoryKeyValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The cheats menu (#51). What matters here is not the UI but the two invariants behind it: the
 * unlock is persisted and reversible, and the cheat data (hands, seed) exists only in the LOCAL
 * game's ViewModel — never in a `PlayerView`, and so never on the wire.
 */
class CheatsUnlockTest {

    @Test
    fun `the unlock is off by default, persists, and can be undone`() = runTest {
        val store = InMemoryKeyValueStore()
        val repo = KeyValueSettingsRepository(store)
        assertFalse(repo.cheatsUnlocked.first(), "cheats must be hidden until deliberately unlocked")

        repo.setCheatsUnlocked(true)
        assertTrue(repo.cheatsUnlocked.first())
        // A fresh repository over the same store: the unlock survives a restart.
        assertTrue(KeyValueSettingsRepository(store).cheatsUnlocked.first(), "unlock must persist")

        repo.setCheatsUnlocked(false)
        assertFalse(repo.cheatsUnlocked.first(), "re-locking must work — a one-way door is a trap")
    }

    @Test
    fun `the persisted key is the one that shipped`() = runTest {
        // Renaming this orphans every unlocked install's flag; pin it like the other settings keys.
        assertEquals("cheats_unlocked", SettingsKeys.CHEATS_UNLOCKED)
        val store = InMemoryKeyValueStore()
        KeyValueSettingsRepository(store).setCheatsUnlocked(true)
        assertEquals(true, store.boolean(SettingsKeys.CHEATS_UNLOCKED).first())
    }
}

class CheatDataSourceTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `all hands and the seed come from the local game, and PlayerView carries neither`() =
        runTest(dispatcher) {
            val vm = GameViewModel()
            vm.newGame(seed = 42L)
            advanceUntilIdle()

            val hands = vm.allHands.value
            assertEquals(4, hands.size, "the local game holds every seat's hand")
            assertTrue(hands.values.all { it.size == 10 }, "…and they are the dealt hands")
            assertEquals(42L, vm.currentSeed.value, "the seed is exposed for show/copy")

            // The redacted view — what a server would send — must expose neither. This is the
            // invariant the cheat is designed around: online there is nothing to leak.
            val view = assertNotNull(vm.humanView.value)
            assertEquals(10, view.hand.size, "own hand only")
            assertEquals(
                setOf(Seat(1), Seat(2), Seat(3)),
                view.handSizes.keys - Seat(0),
                "other seats are visible only as COUNTS",
            )
            assertEquals(null, view.exposedDeclarerHand, "no hand is exposed outside open misère")
        }

    @Test
    fun `a cheat re-deal keeps the table and moves to the requested seed`() = runTest(dispatcher) {
        val vm = GameViewModel()
        vm.newGame(seed = 7L, playerCount = 6, teamCount = 3)
        advanceUntilIdle()
        val before = assertNotNull(vm.humanView.value)

        vm.cheatRedeal(seed = 8L)
        advanceUntilIdle()
        val after = assertNotNull(vm.humanView.value)

        assertEquals(8L, vm.currentSeed.value)
        assertEquals(before.playerCount, after.playerCount, "the table shape is kept")
        assertEquals(before.teamCount, after.teamCount, "…including the team split")
        assertTrue(
            before.hand != after.hand,
            "a re-deal on a different seed must actually deal differently",
        )
    }

    @Test
    fun `every new match bumps the generation the deal animation keys off`() = runTest(dispatcher) {
        // A new match is handNumber 1 again, so the hand number alone cannot tell the game screen
        // "this is a different game". It keys its deal bookkeeping off this counter instead; when it
        // did not, a cheat re-deal was read as a recreation mid-hand — the deal was skipped and its
        // pacing signal never fired, leaving the first bot bid to wait out the gates' whole
        // deadlock backstop (~3x the deal estimate). That is the "bots take ages after a re-deal"
        // report, so the counter advancing is the contract worth pinning.
        val vm = GameViewModel()
        assertEquals(0, vm.gameGeneration.value, "no game yet")
        vm.newGame(seed = 1L)
        advanceUntilIdle()
        val first = vm.gameGeneration.value
        assertTrue(first > 0, "starting a match must bump the generation")

        vm.cheatRedeal(seed = 2L)
        advanceUntilIdle()
        assertTrue(vm.gameGeneration.value > first, "a re-deal is a new match, not the same one")

        // Same seed, same table: still a different match, and still needs its own deal.
        val second = vm.gameGeneration.value
        vm.cheatRedeal(seed = 2L)
        advanceUntilIdle()
        assertTrue(
            vm.gameGeneration.value > second,
            "re-dealing the SAME seed must still count as a new match — the hand number and the " +
                "seed are both unchanged, so this counter is the only signal the UI has",
        )
    }

    @Test
    fun `a re-deal before any game is a no-op rather than a crash`() = runTest(dispatcher) {
        val vm = GameViewModel()
        vm.cheatRedeal(seed = 5L)
        advanceUntilIdle()
        assertEquals(null, vm.humanView.value, "nothing to re-deal, nothing started")
        assertEquals(null, vm.currentSeed.value)
    }
}
