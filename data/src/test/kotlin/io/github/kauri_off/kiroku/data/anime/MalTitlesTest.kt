package io.github.kauri_off.kiroku.data.anime

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.AnimeId
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.NetworkFixture
import io.github.kauri_off.kiroku.data.network.NetworkFixture.Companion.answer
import io.github.kauri_off.kiroku.data.network.UnusableAnswer
import io.github.kauri_off.kiroku.data.network.received
import io.github.kauri_off.kiroku.data.recorder.RecordedAnswers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// The client over recorded answers behind a local server.
class MalTitlesTest {
    @get:Rule
    internal val network = NetworkFixture()

    private val titles by lazy { MalTitles(network.http) }

    private fun serve(name: String) {
        val recorded = RecordedAnswers.named(name)
        network.api.enqueue(answer(recorded.status, recorded.body, "application/json; charset=UTF-8"))
    }

    @Test
    fun `a title's page is asked by its id with the details' fields, and its answer is the mapped page`() {
        serve("title_airing")
        val outcome = runBlocking { titles.title(AnimeId(61987), adult = false) }

        assertEquals(Outcome.Success(recordedTitle("title_airing")), outcome)
        val request = network.api.received()
        assertEquals("/v2/anime/61987", request.url.encodedPath)
        assertEquals(MalFields.DETAILS, request.url.queryParameter("fields"))
        assertNull(request.url.queryParameter("nsfw"))
        assertTrue(network.unexpected.isEmpty())
    }

    @Test
    fun `with adult titles shown the page is asked with nsfw`() {
        serve("title_adult_nsfw_true")
        val outcome = runBlocking { titles.title(AnimeId(368), adult = true) }

        assertEquals(Outcome.Success(recordedTitle("title_adult_nsfw_true")), outcome)
        assertEquals(
            "true",
            network.api
                .received()
                .url
                .queryParameter("nsfw"),
        )
    }

    @Test
    fun `a title MAL does not have is not found`() {
        serve("title_unknown")
        assertEquals(Outcome.Failure(DataError.NotFound), runBlocking { titles.title(AnimeId(2), adult = false) })
        assertTrue(network.unexpected.isEmpty())
    }

    @Test
    fun `an answer the mapper cannot read is a server error, and reported`() {
        val page = recordedJson("title_airing").edited(listOf("status"))
        network.api.enqueue(answer(200, page.toString(), "application/json; charset=UTF-8"))

        assertEquals(Outcome.Failure(DataError.ServerError), runBlocking { titles.title(AnimeId(61987), adult = false) })
        assertTrue(network.unexpected.single() is UnusableAnswer)
    }
}
