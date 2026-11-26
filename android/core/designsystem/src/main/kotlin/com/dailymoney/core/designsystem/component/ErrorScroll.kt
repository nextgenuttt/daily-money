package com.dailymoney.core.designsystem.component

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Brings the first field with a problem into view after a failed save, so a required field that is
 * scrolled off screen (or hidden above the keyboard) is never missed. Fields register with
 * [errorTarget] in top-to-bottom [order]; [trigger] changes on every failed save.
 */
@Stable
class ErrorScroller internal constructor() {
    internal val fields = HashMap<Int, Pair<BringIntoViewRequester, Boolean>>()

    internal suspend fun scrollToFirst() {
        fields.filterValues { it.second }.minByOrNull { it.key }?.value?.first?.bringIntoView()
    }
}

/** [trigger] should change on each failed save (a counter, or the error set when it changes). */
@Composable
fun rememberErrorScroller(trigger: Any?): ErrorScroller {
    val scroller = remember { ErrorScroller() }
    LaunchedEffect(trigger) { if (trigger != null && trigger != 0) scroller.scrollToFirst() }
    return scroller
}

@Composable
fun Modifier.errorTarget(scroller: ErrorScroller, order: Int, hasError: Boolean): Modifier {
    val requester = remember { BringIntoViewRequester() }
    SideEffect { scroller.fields[order] = requester to hasError }
    return bringIntoViewRequester(requester)
}
