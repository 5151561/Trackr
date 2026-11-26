package com.trackr.core.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene

/**
 * 页面转场，和第 16 步之前 `MainActivity` 里用户实际看到的一样：进栈、出栈、预测返回都是横向滑动 + 淡入淡出（300ms）。
 *
 * 旧代码里有一段「两个页签根页之间切换用 220ms 淡入淡出」的判断，但它用 `initialState.key is ScreenKey.Home`，
 * 而 Scene.key 是字符串 `"$key:${key::class}"`，判断永远为假，所以页签切换一直是滑动。第 16 步按「无用户可见变化」
 * 删掉这段死代码；以后要改成淡入淡出另开一步。
 */
object TrackrTransitions {
    private const val SLIDE_MILLIS = 300

    val push: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = {
        (slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(SLIDE_MILLIS)) +
            fadeIn(animationSpec = tween(SLIDE_MILLIS))) togetherWith
            (slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = tween(SLIDE_MILLIS)) +
                fadeOut(animationSpec = tween(SLIDE_MILLIS)))
    }

    val pop: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = {
        popSlide()
    }

    val predictivePop: AnimatedContentTransitionScope<Scene<NavKey>>.(Int) -> ContentTransform = { _ ->
        popSlide()
    }

    private fun popSlide(): ContentTransform =
        (slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = tween(SLIDE_MILLIS)) +
            fadeIn(animationSpec = tween(SLIDE_MILLIS))) togetherWith
            (slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(SLIDE_MILLIS)) +
                fadeOut(animationSpec = tween(SLIDE_MILLIS)))
}
