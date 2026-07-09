// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.rotundtapir.cardkit.core.Card
import io.github.rotundtapir.cardkit.core.Seat
import io.github.rotundtapir.cardkit.ui.CardBack
import io.github.rotundtapir.cardkit.ui.PlayingCard
import io.github.rotundtapir.fivehundred.AnimationSpeed
import io.github.rotundtapir.fivehundred.engine.HAND_SIZE
import io.github.rotundtapir.cardkit.ui.SoundEffect
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The dealing animation for a new hand of 500: card backs fly from a deck in the centre of the
 * felt to each destination in 500's true packet order — a visible packet of 3 to each player then
 * one card to the kitty, then packets of 4 + kitty, then 3 + kitty. Opponents' piles grow in the
 * opponents row, the kitty pile grows on the felt, and the human's cards accumulate face down at
 * the bottom of the screen, then flip face up (with a small left-to-right stagger) when the deal
 * completes.
 *
 * Everything here is presentational: the engine has already dealt. The ViewModel releases the
 * first bidder on the `dealAnimationDone` signal that GameScreen raises when this finishes; its
 * `dealPauseMillis` (derived from [dealTimings]) only scales the deadlock backstop for the case
 * where the signal never comes. At [AnimationSpeed.OFF] none of this runs and
 * [DealAnimationState.stage] stays [DealStage.DONE].
 */
internal enum class DealStage { SHUFFLING, DEALING, FLIPPING, DONE }

/** A destination a dealt card can fly to. Also used as the anchor key for that destination. */
internal sealed interface DealTarget {
    data class SeatPile(val seat: Seat) : DealTarget
    data object Kitty : DealTarget
}

/** Anchor key for the deck the cards fly *from*. */
internal data object DeckAnchor

internal class DealAnimationState {
    var stage by mutableStateOf(DealStage.DONE)

    /**
     * Where deal sounds go: called with [SoundEffect.SHUFFLE] once per riffle and
     * [SoundEffect.CARD_SLIDE] as each packet lands. Null (the default) means silent — the
     * integration layer wires this to a `SoundManager`. Kept as a hook so this file stays free
     * of any audio implementation.
     */
    var soundHook: ((SoundEffect) -> Unit)? = null

    /** Cards landed so far, per destination. */
    val counts = mutableStateMapOf<DealTarget, Int>()

    /** While shuffling: true when the deck's two halves are pulled apart mid-riffle. */
    var shuffleSplit by mutableStateOf(false)

    /** Non-null while exactly one packet is in flight towards this target. */
    var flyingTarget by mutableStateOf<DealTarget?>(null)

    /** How many card backs the in-flight packet contains (3/4 to a seat, 1 to the kitty). */
    var flyingCount by mutableIntStateOf(1)

    /** Centre of the in-flight card, in root coordinates. */
    val flyingPos = Animatable(Offset.Zero, Offset.VectorConverter)

    /** Destination/deck centres in root coordinates, reported by [dealAnchor]. */
    val anchors = mutableStateMapOf<Any, Offset>()

    /** Root offset of the overlay Box the flying card is drawn in. */
    var overlayOrigin by mutableStateOf(Offset.Zero)

    val dealing: Boolean get() = stage != DealStage.DONE
    fun dealtTo(seat: Seat): Int = counts[DealTarget.SeatPile(seat)] ?: 0
    val kittyCount: Int get() = counts[DealTarget.Kitty] ?: 0
}

/** Reports this composable's centre (in root coordinates) as the anchor for [key]. */
internal fun Modifier.dealAnchor(state: DealAnimationState, key: Any): Modifier =
    onGloballyPositioned { coords ->
        state.anchors[key] =
            coords.positionInRoot() + Offset(coords.size.width / 2f, coords.size.height / 2f)
    }

/**
 * Per-speed budgets. The flights self-correct against a deadline (frame quantisation would
 * otherwise accumulate). Packet dealing means only 3×(players+1) flights (15 at four players), so
 * each flight is long enough to actually watch — ~180ms at Normal, ~280ms at Slow. The ViewModel
 * derives its deadlock-backstop hold from these values, so retuning here needs no second edit.
 */
internal data class DealTimings(
    val shuffleMillis: Long,
    val flyBudgetMillis: Long,
    val flipMillis: Int,
    val flipStaggerMillis: Int,
) {
    /**
     * The flip phase's full duration: the base flip plus FlippingCard's per-index stagger across
     * the human's [HAND_SIZE] cards, plus a little slack.
     */
    val flipTotalMillis: Long get() = flipMillis + flipStaggerMillis * (HAND_SIZE - 1L) + FLIP_SLACK_MILLIS
}

private const val FLIP_SLACK_MILLIS = 30L

internal fun dealTimings(speed: AnimationSpeed): DealTimings = when (speed) {
    AnimationSpeed.SLOW -> DealTimings(shuffleMillis = 1_600, flyBudgetMillis = 4_200, flipMillis = 300, flipStaggerMillis = 40)
    AnimationSpeed.FAST -> DealTimings(shuffleMillis = 400, flyBudgetMillis = 1_100, flipMillis = 140, flipStaggerMillis = 15)
    else -> DealTimings(shuffleMillis = 900, flyBudgetMillis = 2_800, flipMillis = 240, flipStaggerMillis = 28)
}

/** Flights shorter than this read as teleports anyway; skip the animation and just land the packet. */
private const val MIN_FLIGHT_MILLIS = 8

/** Fraction of each flight slot spent moving; the rest is a beat between packets. */
private const val FLIGHT_FRACTION = 0.8f

/**
 * Drives one full deal. Suspends until the flip has finished; always leaves the state at
 * [DealStage.DONE] even if cancelled mid-deal.
 */
internal suspend fun runDealAnimation(
    state: DealAnimationState,
    playerCount: Int,
    dealer: Seat,
    speed: AnimationSpeed,
) {
    val timings = dealTimings(speed)
    val totalFlights = 3 * (playerCount + 1)
    try {
        state.counts.clear()
        // Riffle the deck a few times before the first packet flies.
        state.stage = DealStage.SHUFFLING
        val riffles = 3
        repeat(riffles) {
            state.soundHook?.invoke(SoundEffect.SHUFFLE)
            state.shuffleSplit = true
            delay(timings.shuffleMillis / (riffles * 2L))
            state.shuffleSplit = false
            delay(timings.shuffleMillis / (riffles * 2L))
        }
        state.stage = DealStage.DEALING
        val dealStart = TimeSource.Monotonic.markNow()
        var flown = 0
        // Deal order: eldest hand (left of dealer) first, dealer last — a visible packet of 3/4/3
        // cards per seat, then a single card to the kitty after each full round of packets.
        val seats = (1..playerCount).map { Seat((dealer.index + it) % playerCount) }
        suspend fun fly(target: DealTarget, cards: Int) {
            val elapsed = dealStart.elapsedNow().inWholeMilliseconds
            val remaining = timings.flyBudgetMillis - elapsed
            val slot = (remaining / (totalFlights - flown)).toInt()
            state.flyPacket(target, cards, slot)
            flown++
        }
        for (packet in intArrayOf(3, 4, 3)) {
            for (seat in seats) fly(DealTarget.SeatPile(seat), packet)
            fly(DealTarget.Kitty, 1)
        }
        state.stage = DealStage.FLIPPING
        delay(timings.flipTotalMillis)
    } finally {
        state.flyingTarget = null
        state.stage = DealStage.DONE
    }
}

private suspend fun DealAnimationState.flyPacket(target: DealTarget, cards: Int, slotMillis: Int) {
    val from = awaitAnchor(DeckAnchor)
    val to = awaitAnchor(target)
    val flightMillis = (slotMillis * FLIGHT_FRACTION).toInt()
    if (from != null && to != null && flightMillis >= MIN_FLIGHT_MILLIS) {
        flyingPos.snapTo(from)
        flyingCount = cards
        flyingTarget = target
        flyingPos.animateTo(to, tween(flightMillis, easing = FastOutSlowInEasing))
        flyingTarget = null
        counts[target] = (counts[target] ?: 0) + cards
        soundHook?.invoke(SoundEffect.CARD_SLIDE)
        // A short beat between packets so each delivery registers.
        delay((slotMillis - flightMillis).coerceAtLeast(0).toLong())
    } else {
        counts[target] = (counts[target] ?: 0) + cards
    }
}

/** Waits (briefly) for an anchor to be laid out; null if it never appears, so the deal can't hang. */
private suspend fun DealAnimationState.awaitAnchor(key: Any): Offset? =
    anchors[key] ?: withTimeoutOrNull(500L) {
        snapshotFlow { anchors[key] }.filterNotNull().first()
    }

private val FlyingCardWidth = 44.dp
private val FlyingFanStep = 5.dp

/** The in-flight packet — a small fanned stack of card backs — drawn at root coordinates. */
@Composable
internal fun FlyingDealCard(state: DealAnimationState) {
    if (state.flyingTarget == null) return
    Box(
        Modifier.offset {
            val centre = state.flyingPos.value - state.overlayOrigin
            IntOffset(
                (centre.x - FlyingCardWidth.toPx() / 2f).roundToInt(),
                (centre.y - FlyingCardWidth.toPx() * 0.7f).roundToInt(),
            )
        },
    ) {
        repeat(state.flyingCount) { i ->
            Box(Modifier.offset(x = FlyingFanStep * i, y = FlyingFanStep * i / 3)) {
                CardBack(width = FlyingCardWidth)
            }
        }
    }
}

private val KittyCardWidth = 32.dp
private val KittyFanStep = 18.dp

/** A small fanned pile of [count] face-down cards labelled "Kitty"; holds its 3-card footprint. */
@Composable
internal fun KittyPile(count: Int, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box {
            Spacer(Modifier.size(KittyCardWidth + KittyFanStep * 2, KittyCardWidth * 1.4f))
            repeat(count) { i ->
                Box(Modifier.offset(x = KittyFanStep * i)) { CardBack(width = KittyCardWidth) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Kitty", style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Felt centre while a hand is being dealt (and just after): the deck the cards fly out of, plus
 * the growing kitty pile. The deck header collapses once the last card lands, leaving the kitty
 * where the plain bidding-phase kitty renders.
 */
@Composable
internal fun DealFelt(state: DealAnimationState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(
            visible = state.stage == DealStage.SHUFFLING || state.stage == DealStage.DEALING,
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (state.stage == DealStage.SHUFFLING) "Shuffling…" else "Dealing…",
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))
                // While shuffling, the deck splits into two half-stacks that riffle back together.
                val split by animateDpAsState(
                    targetValue = if (state.shuffleSplit) 30.dp else 0.dp,
                    animationSpec = tween(120),
                    label = "shuffleSplit",
                )
                val tilt by animateFloatAsState(
                    targetValue = if (state.shuffleSplit) 7f else 0f,
                    animationSpec = tween(120),
                    label = "shuffleTilt",
                )
                // Lambda offset + graphicsLayer keep the riffle in the placement/draw phases:
                // reading `split`/`tilt` during composition would recompose (and the plain
                // offset(x=) overload relayout) every animation frame — visible jank on wasm.
                Box(Modifier.dealAnchor(state, DeckAnchor)) {
                    Box(
                        Modifier
                            .offset { IntOffset(-split.roundToPx(), 0) }
                            .graphicsLayer { rotationZ = -tilt },
                    ) {
                        repeat(2) { i ->
                            Box(Modifier.offset(x = 1.5.dp * i, y = 1.5.dp * i)) { CardBack(width = 48.dp) }
                        }
                    }
                    Box(
                        Modifier
                            .offset { IntOffset(split.roundToPx(), 0) }
                            .graphicsLayer { rotationZ = tilt },
                    ) {
                        repeat(2) { i ->
                            Box(Modifier.offset(x = 1.5.dp * i, y = 1.5.dp * i)) { CardBack(width = 48.dp) }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
        KittyPile(count = state.kittyCount, modifier = Modifier.dealAnchor(state, DealTarget.Kitty))
    }
}

private val OpponentPileStep = 1.2.dp

/**
 * An opponent's card-back pile. While dealing it thickens as each flown packet lands (a faint
 * outline marks the empty slot before the first card arrives); afterwards it stays a stack whose
 * thickness tracks the cards actually left in that hand, thinning as they are played. Both forms
 * occupy the same footprint so the row never jumps.
 */
@Composable
internal fun OpponentPile(seat: Seat, state: DealAnimationState, width: Dp, handSize: Int) {
    Box(
        Modifier
            .size(width + OpponentPileStep * 4, width * 1.4f + OpponentPileStep * 4)
            .dealAnchor(state, DealTarget.SeatPile(seat)),
    ) {
        val cards = if (state.dealing) state.dealtTo(seat) else handSize
        // One visual layer per two cards or so, capped: 10 cards ≈ 5 layers, thinning as they play.
        val layers = ((cards + 1) / 2).coerceAtMost(5)
        if (layers == 0) {
            Box(Modifier.alpha(0.25f)) { CardBack(width = width) }
        } else {
            repeat(layers) { i ->
                Box(Modifier.offset(x = OpponentPileStep * i, y = OpponentPileStep * i)) {
                    CardBack(width = width)
                }
            }
        }
    }
}

private val DealHandCardWidth = 64.dp

/**
 * The human's hand area while dealing/flipping: face-down backs accumulate as cards land, then
 * every card flips face up (Y-axis rotation, staggered left to right) revealing the same order
 * the interactive hand will render in. Replaces the ActionArea until the flip completes.
 */
@Composable
internal fun DealingHandRow(
    cards: List<Card>,
    state: DealAnimationState,
    humanSeat: Seat,
    timings: DealTimings,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("You", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .dealAnchor(state, DealTarget.SeatPile(humanSeat)),
            contentAlignment = Alignment.Center,
        ) {
            // Hold the row's height before the first card lands so the layout doesn't jump.
            Spacer(Modifier.height(DealHandCardWidth * 1.4f))
            Row(horizontalArrangement = Arrangement.spacedBy(-DealHandCardWidth * 0.45f)) {
                if (state.stage == DealStage.FLIPPING) {
                    cards.forEachIndexed { i, card ->
                        FlippingCard(card, i, DealHandCardWidth, timings)
                    }
                } else {
                    repeat(state.dealtTo(humanSeat)) { CardBack(width = DealHandCardWidth) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** One card of the reveal: back rotates 0→90°, then the face (pre-mirrored) carries 90°→180°. */
@Composable
private fun FlippingCard(card: Card, index: Int, width: Dp, timings: DealTimings) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * timings.flipStaggerMillis.toLong())
        rotation.animateTo(180f, tween(timings.flipMillis, easing = FastOutSlowInEasing))
    }
    // The rotation itself is read only inside graphicsLayer (draw phase); the back/face switch
    // goes through derivedStateOf so each card recomposes exactly once (at 90°) rather than on
    // every frame of the flip — ten cards recomposing per frame stuttered visibly on wasm.
    val showBack by remember { derivedStateOf { rotation.value <= 90f } }
    Box(
        Modifier.graphicsLayer {
            rotationY = rotation.value
            cameraDistance = 8f * density
        },
    ) {
        if (showBack) {
            CardBack(width = width)
        } else {
            Box(Modifier.graphicsLayer { rotationY = 180f }) { PlayingCard(card, width = width) }
        }
    }
}
