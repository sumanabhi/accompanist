/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("NOTHING_TO_INLINE")

package com.google.accompanist.lazysnap

import androidx.compose.animation.core.AnimationScope
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.truncate

private const val DebugLog = true

@RequiresOptIn(message = "Accompanist Lazy Snap is experimental. The API may be changed in the future.")
@Retention(AnnotationRetention.BINARY)
annotation class ExperimentalLazySnapApi

/**
 * Default values used for [SnappingFlingBehavior] & [rememberSnappingFlingBehavior].
 */
@ExperimentalLazySnapApi
object SnappingFlingBehaviorDefaults {
    /**
     * TODO
     */
    val snapAnimationSpec: AnimationSpec<Float> = spring(stiffness = 400f)

    val maximumFlingDistance: (LazyListLayoutInfo) -> Int = { Int.MAX_VALUE }
}

/**
 * Create and remember a snapping [FlingBehavior] to be used with [LazyListState].
 *
 * @param lazyListState The [LazyListState] to update.
 * @param snapOffsetForItem Block which returns which offset the given item should 'snap' to.
 * See [SnapOffsets] for provided values.
 * @param maximumFlingDistance Block which returns the maximum fling distance in pixels.
 * @param decayAnimationSpec The decay animation spec to use for decayed flings.
 * @param snapAnimationSpec The animation spec to use when snapping.
 */
@ExperimentalLazySnapApi
@Composable
fun rememberSnappingFlingBehavior(
    lazyListState: LazyListState,
    snapOffsetForItem: (layoutInfo: LazyListLayoutInfo, itemInfo: LazyListItemInfo) -> Int = SnapOffsets.Center,
    maximumFlingDistance: (LazyListLayoutInfo) -> Int = SnappingFlingBehaviorDefaults.maximumFlingDistance,
    decayAnimationSpec: DecayAnimationSpec<Float> = rememberSplineBasedDecay(),
    snapAnimationSpec: AnimationSpec<Float> = SnappingFlingBehaviorDefaults.snapAnimationSpec,
): SnappingFlingBehavior = remember(
    lazyListState,
    snapOffsetForItem,
    maximumFlingDistance,
    decayAnimationSpec,
    snapAnimationSpec
) {
    SnappingFlingBehavior(
        lazyListState = lazyListState,
        snapOffsetForItem = snapOffsetForItem,
        maximumFlingDistance = maximumFlingDistance,
        decayAnimationSpec = decayAnimationSpec,
        snapAnimationSpec = snapAnimationSpec,
    )
}

/**
 * Contains a number of values which can be used for the `snapOffsetForItem` parameter on
 * [rememberSnappingFlingBehavior] and [SnappingFlingBehavior].
 */
@Suppress("unused")
object SnapOffsets {
    /**
     * Snap offset which results in the start edge of the item, snapping to the start scrolling
     * edge of the lazy list.
     */
    val Start: (LazyListLayoutInfo, LazyListItemInfo) -> Int = { _, _ -> 0 }

    /**
     * Snap offset which results in the item snapping in the center of the scrolling viewport
     * of the lazy list.
     */
    val Center: (LazyListLayoutInfo, LazyListItemInfo) -> Int = { layoutInfo, itemInfo ->
        (layoutInfo.layoutSize - itemInfo.size) / 2
    }

    /**
     * Snap offset which results in the end edge of the item, snapping to the end scrolling
     * edge of the lazy list.
     */
    val End: (LazyListLayoutInfo, LazyListItemInfo) -> Int = { layoutInfo, itemInfo ->
        layoutInfo.layoutSize - itemInfo.size
    }
}

/**
 * A snapping [FlingBehavior] for [LazyListState]. Typically this would be created
 * via [rememberSnappingFlingBehavior].
 *
 * Note: the default parameter value for [decayAnimationSpec] is different to the value used in
 * [rememberSnappingFlingBehavior], due to not being able to access composable functions.
 *
 * @param lazyListState The [LazyListState] to update.
 * @param snapOffsetForItem Block which returns which offset the given item should 'snap' to.
 * See [SnapOffsets] for provided values.
 * @param maximumFlingDistance Block which returns the maximum fling distance in pixels.
 * @param decayAnimationSpec The decay animation spec to use for decayed flings.
 * @param snapAnimationSpec The animation spec to use when snapping.
 */
@ExperimentalLazySnapApi
class SnappingFlingBehavior(
    private val lazyListState: LazyListState,
    private val snapOffsetForItem: (LazyListLayoutInfo, LazyListItemInfo) -> Int = SnapOffsets.Center,
    private val maximumFlingDistance: LazyListLayoutInfo.() -> Int = SnappingFlingBehaviorDefaults.maximumFlingDistance,
    private val decayAnimationSpec: DecayAnimationSpec<Float> = exponentialDecay(),
    private val snapAnimationSpec: AnimationSpec<Float> = SnappingFlingBehaviorDefaults.snapAnimationSpec,
) : FlingBehavior {
    /**
     * The target item index for any on-going animations.
     */
    var animationTarget: Int? by mutableStateOf(null)
        private set

    override suspend fun ScrollScope.performFling(
        initialVelocity: Float
    ): Float {
        if (DebugLog) {
            Napier.d(message = { "performFling. initialVelocity: $initialVelocity" })
        }

        val itemInfo = currentItemInfo ?: return initialVelocity

        return if (decayAnimationSpec.canFlingPastCurrentItem(itemInfo, initialVelocity)) {
            // If the decay fling can scroll past the current item, fling with decay
            performDecayFling(
                targetIndex = determineTargetIndexForDecay(initialVelocity, itemInfo),
                initialVelocity = initialVelocity,
            )
        } else {
            // Otherwise we 'spring' to current/next item
            performSpringFling(
                targetIndex = determineTargetIndexForSpring(initialVelocity, itemInfo),
                initialVelocity = initialVelocity,
            )
        }
    }

    private suspend fun ScrollScope.performDecayFling(
        targetIndex: Int,
        initialVelocity: Float,
    ): Float {
        Napier.d(
            message = {
                "Performing decay fling. " +
                    "vel:$initialVelocity, " +
                    "current item: ${currentItemInfo?.log()}, " +
                    "target: $targetIndex"
            }
        )

        var velocityLeft = initialVelocity
        var lastValue = 0f

        try {
            // Update the animationTarget
            animationTarget = targetIndex

            AnimationState(
                initialValue = 0f,
                initialVelocity = initialVelocity,
            ).animateDecay(decayAnimationSpec) {
                val delta = value - lastValue
                val consumed = scrollBy(delta)
                lastValue = value
                velocityLeft = velocity

                if (checkSnapBack(initialVelocity, targetIndex, ::scrollBy)) {
                    cancelAnimation()
                } else if (abs(delta - consumed) > 0.5f) {
                    // If we're still running but some of the scroll was not consumed,
                    // cancel the animation now
                    cancelAnimation()
                }
            }
        } finally {
            animationTarget = null
        }

        if (DebugLog) {
            Napier.d(
                message = {
                    "Decay fling finished. Distance: $lastValue. Final vel: $velocityLeft"
                }
            )
        }

        return velocityLeft
    }

    private fun determineTargetIndexForDecay(
        initialVelocity: Float,
        currentItem: LazyListItemInfo,
    ): Int {
        val distancePerChild = lazyListState.layoutInfo.computeDistancePerChild()
        if (distancePerChild <= 0) {
            // If we don't have a valid distance, return the current item
            return currentItem.index
        }

        val maximumFlingDistance = maximumFlingDistance(lazyListState.layoutInfo).toFloat()
        val flingDistance = decayAnimationSpec.calculateTargetValue(0f, initialVelocity)
            .coerceIn(-maximumFlingDistance, maximumFlingDistance)

        val indexDelta = truncate((flingDistance - currentItem.offset) / distancePerChild).toInt()

        if (DebugLog) {
            Napier.d(
                message = {
                    "determineTargetIndexForDecay. " +
                        "currentItem: ${currentItem.log()}, " +
                        "distancePerChild: $distancePerChild, " +
                        "maximumFlingDistance: $maximumFlingDistance, " +
                        "flingDistance: $flingDistance, " +
                        "indexDelta: $indexDelta"
                }
            )
        }

        return (currentItem.index + indexDelta).coerceIn(0, lazyListState.layoutInfo.lastIndex)
    }

    @Suppress("unused_parameter")
    private fun determineTargetIndexForSpring(
        initialVelocity: Float,
        currentItemInfo: LazyListItemInfo,
    ): Int {
        // We can't trust the velocity right now. We're waiting on
        // https://android-review.googlesource.com/c/platform/frameworks/support/+/1826965/,
        // which will be available in Compose Foundation 1.1.
        // TODO: uncomment this once we move to Compose Foundation 1.1
        // if (initialVelocity.absoluteValue > 1) {
        //    // If the velocity isn't zero, spring in the relevant direction
        //    return when {
        //        initialVelocity > 0 -> {
        //            (currentItemInfo.index + 1).coerceIn(0, lazyListState.layoutInfo.lastIndex)
        //        }
        //        else -> currentItemInfo.index
        //    }
        // }

        // Otherwise we look at the current offset, and spring to whichever is closer
        val snapOffset = snapOffsetForItem(lazyListState.layoutInfo, currentItemInfo)
        return when {
            currentItemInfo.offset < snapOffset - (currentItemInfo.size / 2) -> {
                (currentItemInfo.index + 1).coerceIn(0, lazyListState.layoutInfo.lastIndex)
            }
            else -> currentItemInfo.index
        }
    }

    private suspend fun ScrollScope.performSpringFling(
        targetIndex: Int,
        initialVelocity: Float = 0f,
    ): Float {
        // If we don't have a current layout, we can't snap
        val initialItem = currentItemInfo ?: return initialVelocity

        Napier.d(
            message = {
                "Performing spring. " +
                    "vel:$initialVelocity, " +
                    "current item: ${initialItem.log()}, " +
                    "target: $targetIndex"
            }
        )

        val itemSpacing = lazyListState.layoutInfo.itemSpacing
        var velocityLeft = initialVelocity
        var lastValue = 0f

        try {
            // Update the animationTarget
            animationTarget = targetIndex

            AnimationState(
                initialValue = 0f,
                initialVelocity = initialVelocity,
            ).animateTo(
                targetValue = when {
                    targetIndex > initialItem.index -> initialItem.size + itemSpacing
                    else -> -(initialItem.size + itemSpacing)
                }.toFloat(),
                animationSpec = snapAnimationSpec,
            ) {
                val delta = value - lastValue
                val consumed = scrollBy(delta)
                lastValue = value
                velocityLeft = velocity

                if (checkSnapBack(initialVelocity, targetIndex, ::scrollBy)) {
                    cancelAnimation()
                } else if (abs(delta - consumed) > 0.5f) {
                    // If we're still running but some of the scroll was not consumed,
                    // cancel the animation now
                    cancelAnimation()
                }
            }
        } finally {
            animationTarget = null
        }

        if (DebugLog) {
            Napier.d(
                message = {
                    "Spring fling finished. Distance: $lastValue. Final vel: $velocityLeft"
                }
            )
        }

        return velocityLeft
    }

    /**
     * Returns true if we needed to perform a snap back, and the animation should be cancelled.
     */
    private inline fun AnimationScope<Float, AnimationVector1D>.checkSnapBack(
        initialVelocity: Float,
        targetIndex: Int,
        scrollBy: (pixels: Float) -> Float,
    ): Boolean {
        val current = currentItemInfo
        if (current == null) {
            cancelAnimation()
            return true
        }

        Napier.d(
            message = {
                "scroll tick. vel:$velocity, current item: ${current.log()}"
            }
        )

        // Calculate the 'snap back'. If the returned value is 0, we don't need to do anything.
        val snapBackAmount = calculateSnapBack(initialVelocity, current, targetIndex)

        if (snapBackAmount != 0) {
            // If we've scrolled to/past the item, stop the animation. We may also need to
            // 'snap back' to the item as we may have scrolled past it
            Napier.d(
                message = {
                    "Scrolled past item. " +
                        "vel:$initialVelocity, " +
                        "current item: ${current.log()}, " +
                        "target:$targetIndex"
                }
            )
            scrollBy(snapBackAmount.toFloat())
            return true
        }

        return false
    }

    private val currentItemInfo: LazyListItemInfo?
        get() = lazyListState.layoutInfo.let { layoutInfo ->
            layoutInfo.visibleItemsInfo.asSequence()
                .filter { it.offset <= snapOffsetForItem(layoutInfo, it) }
                .lastOrNull()
        }

    private fun DecayAnimationSpec<Float>.canFlingPastCurrentItem(
        currentItem: LazyListItemInfo,
        initialVelocity: Float,
    ): Boolean {
        // If we don't have a velocity, return false
        if (initialVelocity.absoluteValue < 0.5f) return false

        val targetValue = calculateTargetValue(
            initialValue = currentItem.offset.toFloat(),
            initialVelocity = initialVelocity,
        )
        val snapOffset = snapOffsetForItem(lazyListState.layoutInfo, currentItem)
        val itemSpacing = lazyListState.layoutInfo.itemSpacing

        if (DebugLog) {
            Napier.d(
                message = {
                    "canFlingPastCurrentItem. " +
                        "initialVelocity: $initialVelocity, " +
                        "currentItem: ${currentItem.log()}, " +
                        "targetValue: $targetValue, " +
                        "snapOffset: $snapOffset, " +
                        "itemSpacing: $itemSpacing"
                }
            )
        }

        return when {
            // forwards
            initialVelocity < 0 -> targetValue <= snapOffset - (currentItem.size + itemSpacing)
            // backwards
            else -> targetValue >= snapOffset + itemSpacing
        }
    }

    private fun calculateSnapBack(
        initialVelocity: Float,
        currentItem: LazyListItemInfo,
        targetIndex: Int,
    ): Int = when {
        // forwards
        initialVelocity > 0 && currentItem.index >= targetIndex -> {
            val target = lazyListState.layoutInfo.visibleItemsInfo.first { it.index == targetIndex }
            val targetScrollOffset = snapOffsetForItem(lazyListState.layoutInfo, target)
            when {
                // We've scrolled past the target index
                currentItem.index > targetIndex -> {
                    target.offset - targetScrollOffset
                }
                // The current item is the target, but we've scrolled past it
                currentItem.index == targetIndex && currentItem.offset < targetScrollOffset -> {
                    target.offset - targetScrollOffset
                }
                else -> 0
            }
        }
        initialVelocity <= 0 && currentItem.index <= targetIndex -> {
            // backwards
            val target = lazyListState.layoutInfo.visibleItemsInfo.first { it.index == targetIndex }
            val targetScrollOffset = snapOffsetForItem(lazyListState.layoutInfo, target)
            when {
                // We've scrolled past the target index
                currentItem.index < targetIndex -> {
                    target.offset - targetScrollOffset
                }
                // The current item is the target, but we've scrolled past it
                currentItem.index == targetIndex && currentItem.offset > targetScrollOffset -> {
                    target.offset - targetScrollOffset
                }
                else -> 0
            }
        }
        else -> 0
    }

    private companion object {
        init {
            if (DebugLog) {
                Napier.base(DebugAntilog(defaultTag = "SnappingFlingBehavior"))
            }
        }
    }
}

internal inline fun LazyListItemInfo.log(): String = "[i:$index,o:$offset,s:$size]"

private val LazyListLayoutInfo.lastIndex: Int
    get() = (totalItemsCount - 1).coerceAtLeast(0)

/**
 * Ideally this would exist on [LazyListLayoutInfo] but it doesn't right now.
 * Raised https://issuetracker.google.com/issues/200920410 to track.
 */
private val LazyListLayoutInfo.layoutSize: Int
    get() {
        // Instead we look at the first item with a non-zero size
        return visibleItemsInfo.firstOrNull { it.size > 0 }?.size
            // Or the viewport (but the viewport contains the content padding)
            ?: viewportEndOffset + viewportStartOffset
    }

/**
 * This attempts to calculate the item spacing for the layout, by looking at the distance
 * between the visible items. If there's only 1 visible item available, it returns 0.
 */
private val LazyListLayoutInfo.itemSpacing: Int
    get() = if (visibleItemsInfo.size >= 2) {
        val first = visibleItemsInfo[0]
        val second = visibleItemsInfo[1]
        second.offset - (first.size + first.offset)
    } else 0

/**
 * Computes an average pixel value to pass a single child.
 *
 * Returns a negative value if it cannot be calculated.
 *
 * @return A float value that is the average number of pixels needed to scroll by one view in
 * the relevant direction.
 */
private fun LazyListLayoutInfo.computeDistancePerChild(): Float {
    if (visibleItemsInfo.isEmpty()) return -1f

    val minPosView = visibleItemsInfo.minByOrNull { it.offset } ?: return -1f
    val maxPosView = visibleItemsInfo.maxByOrNull { it.offset + it.size } ?: return -1f

    val start: Int = min(minPosView.offset, maxPosView.offset)
    val end: Int = max(minPosView.offset + minPosView.size, maxPosView.offset + maxPosView.size)
    val distance = end - start

    return when {
        distance != 0 -> distance.toFloat() / visibleItemsInfo.size
        else -> -1f
    }
}
