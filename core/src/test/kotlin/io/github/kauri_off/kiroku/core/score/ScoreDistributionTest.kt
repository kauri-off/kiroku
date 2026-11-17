package io.github.kauri_off.kiroku.core.score

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScoreDistributionTest {
    private val asked = mutableListOf<AnimeId>()
    private val answers = mutableMapOf<AnimeId, Outcome<ScoreDistribution?>>()
    private var held: CompletableDeferred<Unit>? = null
    private val kept =
        KeptScoreDistributions { id ->
            asked += id
            held?.await()
            answers.getValue(id)
        }

    private val scored = ScoreDistribution(mapOf(10 to 5, 9 to 3, 1 to 1))

    @Test
    fun `a score without votes counts none, and the total is the votes of every score`() {
        assertEquals(5, scored[10])
        assertEquals(0, scored[5])
        assertEquals(9, scored.total)
        assertEquals(0, ScoreDistribution(emptyMap()).total)
        assertEquals(ScoreDistribution(mapOf(3 to 0)), ScoreDistribution(emptyMap()))
    }

    @Test
    fun `a score outside one to ten or a count below zero is not a distribution`() {
        assertThrows(IllegalArgumentException::class.java) { ScoreDistribution(mapOf(11 to 1)) }
        assertThrows(IllegalArgumentException::class.java) { ScoreDistribution(mapOf(0 to 1)) }
        assertThrows(IllegalArgumentException::class.java) { ScoreDistribution(mapOf(5 to -1)) }
    }

    @Test
    fun `a title's distribution and a title the service has no record of are each asked once for the process`() =
        runTest {
            answers[ONE] = Outcome.Success(scored)
            answers[TWO] = Outcome.Success(null)

            repeat(3) {
                assertEquals(Outcome.Success(scored), kept.distribution(ONE))
                assertEquals(Outcome.Success(null), kept.distribution(TWO))
            }

            assertEquals(listOf(ONE, TWO), asked)
        }

    @Test
    fun `a failure is not kept, so the next ask asks again`() =
        runTest {
            answers[ONE] = Outcome.Failure(DataError.ServerError)
            assertEquals(Outcome.Failure(DataError.ServerError), kept.distribution(ONE))

            answers[ONE] = Outcome.Success(scored)
            assertEquals(Outcome.Success(scored), kept.distribution(ONE))
            assertEquals(Outcome.Success(scored), kept.distribution(ONE))

            assertEquals(listOf(ONE, ONE), asked)
        }

    @Test
    fun `asks made at once for one title wait for one request, and another title is not held by it`() =
        runTest {
            answers[ONE] = Outcome.Success(scored)
            answers[TWO] = Outcome.Success(null)
            held = CompletableDeferred()

            val first = async { kept.distribution(ONE) }
            val second = async { kept.distribution(ONE) }
            runCurrent()
            assertEquals(listOf(ONE), asked)
            held!!.complete(Unit)

            assertEquals(Outcome.Success(scored), first.await())
            assertEquals(Outcome.Success(scored), second.await())
            assertEquals(Outcome.Success(null), kept.distribution(TWO))
            assertEquals(listOf(ONE, TWO), asked)
        }

    private companion object {
        val ONE = AnimeId(1)
        val TWO = AnimeId(2)
    }
}
