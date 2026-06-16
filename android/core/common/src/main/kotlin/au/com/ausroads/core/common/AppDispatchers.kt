/*
 * App-wide dispatcher abstraction. Lets us inject a test dispatcher for unit tests and
 * keeps callers from depending on Dispatchers.IO directly.
 */
package au.com.ausroads.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Thin wrapper around the standard kotlinx-coroutines dispatchers. Provided as an
 * interface so unit tests can substitute a TestDispatcher.
 */
interface AppDispatchers {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher

    object Default : AppDispatchers {
        override val main: CoroutineDispatcher = Dispatchers.Main
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }
}
