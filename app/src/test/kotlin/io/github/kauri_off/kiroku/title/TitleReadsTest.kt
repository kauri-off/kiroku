package io.github.kauri_off.kiroku.title

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TitleReadsTest {
    // A value whose key is new waits for its own key's read, though the previous read is still running.
    @Test
    fun `withRead pairs each value only with what was read for its own key`() =
        runTest {
            val values = MutableStateFlow(Keyed(1, "a"))
            val pairs = mutableListOf<Pair<Keyed, Int>>()
            val collecting =
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    values
                        .withRead({ it.key }) { key ->
                            flow {
                                delay(READ_MILLIS)
                                emit(key)
                            }
                        }.collect { pairs += it }
                }
            advanceUntilIdle()
            values.value = Keyed(2, "b")
            advanceUntilIdle()
            values.value = Keyed(2, "c")
            advanceUntilIdle()
            collecting.cancel()
            assertEquals(listOf(Keyed(1, "a") to 1, Keyed(2, "b") to 2, Keyed(2, "c") to 2), pairs)
        }

    private data class Keyed(
        val key: Int,
        val text: String,
    )

    private companion object {
        const val READ_MILLIS = 10L
    }
}
