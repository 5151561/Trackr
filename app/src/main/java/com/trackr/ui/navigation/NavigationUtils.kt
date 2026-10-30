package com.trackr.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 包装 6 个参数的回调函数，仅当 Lifecycle 处于 RESUMED 状态时才执行。
 * 用于防抖及防止快速连击导致的多次入栈。
 */
@Composable
fun <P1, P2, P3, P4, P5, P6> dropUnlessResumed(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    block: (P1, P2, P3, P4, P5, P6) -> Unit
): (P1, P2, P3, P4, P5, P6) -> Unit {
    return { p1, p2, p3, p4, p5, p6 ->
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            block(p1, p2, p3, p4, p5, p6)
        }
    }
}

/**
 * 包装 7 个参数的回调函数，仅当 Lifecycle 处于 RESUMED 状态时才执行。
 * 用于防抖及防止快速连击导致的多次入栈。
 */
@Composable
fun <P1, P2, P3, P4, P5, P6, P7> dropUnlessResumed(
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    block: (P1, P2, P3, P4, P5, P6, P7) -> Unit
): (P1, P2, P3, P4, P5, P6, P7) -> Unit {
    return { p1, p2, p3, p4, p5, p6, p7 ->
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            block(p1, p2, p3, p4, p5, p6, p7)
        }
    }
}
