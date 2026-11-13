package io.github.kauri_off.kiroku.data.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

class MalBackOffTest {
    private val clock = TestClock()
    private val backOff = MalBackOff(clock)

    private fun pass(time: Duration) {
        clock.now += time
    }

    @Test
    fun `background work may call MAL until MAL refuses`() {
        assertEquals(Duration.ZERO, backOff.remaining())
        backOff.refused(null)
        assertEquals(MalBackOff.First, backOff.remaining())
        pass(MalBackOff.First.minusSeconds(1))
        assertEquals(Duration.ofSeconds(1), backOff.remaining())
        pass(Duration.ofSeconds(1))
        assertEquals(Duration.ZERO, backOff.remaining())
    }

    @Test
    fun `a refusal after the wait ran out doubles the wait, up to the longest`() {
        backOff.refused(null)
        pass(MalBackOff.First)
        backOff.refused(null)
        assertEquals(MalBackOff.First.multipliedBy(2), backOff.remaining())
        repeat(10) {
            pass(backOff.remaining())
            backOff.refused(null)
        }
        assertEquals(MalBackOff.Longest, backOff.remaining())
    }

    @Test
    fun `refusals inside one wait start the same wait again`() {
        repeat(10) {
            backOff.refused(null)
            pass(Duration.ofSeconds(1))
        }
        backOff.refused(null)
        assertEquals(MalBackOff.First, backOff.remaining())
    }

    @Test
    fun `an answer ends the wait and the next refusal starts from the first wait`() {
        backOff.refused(null)
        pass(MalBackOff.First)
        backOff.refused(null)
        backOff.answered()
        assertEquals(Duration.ZERO, backOff.remaining())
        backOff.refused(null)
        assertEquals(MalBackOff.First, backOff.remaining())
    }

    @Test
    fun `the time MAL names is waited for when it is longer, up to the longest`() {
        backOff.refused(Duration.ofHours(1))
        assertEquals(Duration.ofHours(1), backOff.remaining())
        backOff.answered()
        backOff.refused(Duration.ofSeconds(5))
        assertEquals(MalBackOff.First, backOff.remaining())
        backOff.answered()
        backOff.refused(Duration.ofDays(30))
        assertEquals(MalBackOff.Longest, backOff.remaining())
    }
}
