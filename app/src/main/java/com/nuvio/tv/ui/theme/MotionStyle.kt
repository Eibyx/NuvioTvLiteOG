package com.nuvio.tv.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How much the interface moves: focus zoom on posters, row scrolling and screen transitions.
 * ORIGINAL keeps Lite's look; CINEMATIC follows Nuvio Arc's cinematic focus (posters grow 5–6 %).
 */
enum class MotionStyle(
    /** Smallest focus zoom for poster cards (ORIGINAL keeps each card's own value). */
    val posterFocusScale: Float?,
    val screenTransitionMs: Int,
    /** Screens also grow slightly in and out instead of only fading. */
    val scaleScreens: Boolean
) {
    ORIGINAL(posterFocusScale = null, screenTransitionMs = 180, scaleScreens = false),
    SMOOTH(posterFocusScale = 1.04f, screenTransitionMs = 240, scaleScreens = false),
    CINEMATIC(posterFocusScale = 1.06f, screenTransitionMs = 280, scaleScreens = true);

    companion object {
        const val PREFERENCE_KEY = "motion_style"
        fun fromStored(value: String?): MotionStyle = entries.firstOrNull { it.name == value } ?: ORIGINAL
    }
}

val LocalMotionStyle = staticCompositionLocalOf { MotionStyle.ORIGINAL }

/** The focus zoom a poster card uses: its own [base], or more with the smooth styles. */
@Composable
fun motionFocusScale(base: Float): Float =
    LocalMotionStyle.current.posterFocusScale?.let { maxOf(it, base) } ?: base

/**
 * Row scrolling for the smooth styles: a critically damped spring (no overshoot) that glides to
 * the focused card and follows quick D-pad presses without restarting. Same target as the
 * original spec, so focus lands in the same place.
 */
@OptIn(ExperimentalFoundationApi::class)
fun motionScrollSpec(style: MotionStyle, base: BringIntoViewSpec): BringIntoViewSpec {
    if (style == MotionStyle.ORIGINAL) return base
    val animation: AnimationSpec<Float> = when (style) {
        MotionStyle.SMOOTH -> spring(dampingRatio = 1f, stiffness = 260f)
        else -> spring(dampingRatio = 1f, stiffness = 200f)
    }
    return object : BringIntoViewSpec {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override val scrollAnimationSpec: AnimationSpec<Float> = animation

        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            base.calculateScrollDistance(offset, size, containerSize)
    }
}

internal val motionScreenEasing = FastOutSlowInEasing
internal fun <T> motionTween(ms: Int): androidx.compose.animation.core.TweenSpec<T> = tween(ms, easing = motionScreenEasing)

/**
 * Focus zoom for cards drawn without TV Material's own scale (the modern home and Continue
 * Watching rows). Nothing changes with [MotionStyle.ORIGINAL]; [paused] skips it while rows
 * scroll fast so quick D-pad presses stay light.
 */
@Composable
fun Modifier.motionFocusZoom(focused: Boolean, paused: Boolean = false, cardScale: Float = 1f): Modifier {
    val style = LocalMotionStyle.current
    val target = style.posterFocusScale?.takeIf { focused && !paused }?.let { maxOf(it, cardScale) } ?: 1f
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = motionTween(if (style == MotionStyle.CINEMATIC) 200 else 150),
        label = "motionFocusZoom"
    )
    if (style.posterFocusScale == null) return this
    return this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}
