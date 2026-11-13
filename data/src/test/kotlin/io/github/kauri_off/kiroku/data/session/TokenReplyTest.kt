package io.github.kauri_off.kiroku.data.session

import io.github.kauri_off.kiroku.core.error.DataError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenReplyTest {
    private val printed = resource("mal/authorization/token.json")

    @Test
    fun thePrintedAnswerGrantsItsTwoTokens() {
        val result = TokenReply.Answer(200, printed).read() as TokenResult.Granted
        assertEquals("ACCESS_TOKEN", result.grant.access)
        assertEquals("REFRESH_TOKEN", result.grant.refresh)
    }

    @Test
    fun aSuccessWithoutBothTokensGrantsNothingAndRejectsNothing() {
        val bodies =
            listOf(
                printed.replace("ACCESS_TOKEN", ""),
                printed.replace("REFRESH_TOKEN", " "),
                printed.replace("\"ACCESS_TOKEN\"", "null"),
                printed.replace("\"REFRESH_TOKEN\"", "7"),
                printed.replace("access_token", "access"),
                printed.replace("refresh_token", "refresh"),
                printed.dropLast(3),
                "",
                "[]",
            )
        for (body in bodies) assertEquals(body, TokenResult.Failed(DataError.ServerError), TokenReply.Answer(200, body).read())
    }

    @Test
    fun theTokensOfAnAnswerThatDidNotSucceedDoNotCount() {
        val statuses = listOf(199, 300, 400, 401, 403, 500)
        for (status in statuses) assertFalse("$status", TokenReply.Answer(status, printed).read() is TokenResult.Granted)
    }

    @Test
    fun onlyTheStatusesOfAnInvalidGrantReject() {
        for (status in 300..599) {
            val expected = if (status == 400 || status == 401) TokenResult.Rejected else TokenResult.Failed(DataError.ServerError)
            assertEquals("$status", expected, TokenReply.Answer(status, "").read())
        }
    }

    @Test
    fun noAnswerKeepsItsError() {
        assertEquals(TokenResult.Failed(DataError.NoConnection), TokenReply.NoAnswer(DataError.NoConnection).read())
    }

    @Test
    fun aRefreshSendsTheGrantAsPrinted() {
        assertEquals(mapOf("grant_type" to "refresh_token", "refresh_token" to "r1"), refreshGrant("r1"))
    }

    @Test
    fun noTextOfTheTokensHoldsOne() {
        val tokens = Tokens("sign-in", "secret-access", "secret-refresh", 0)
        val texts =
            listOf(
                tokens,
                Stored.SignedIn(tokens),
                TokenGrant("secret-access", "secret-refresh"),
                TokenResult.Granted(TokenGrant("secret-access", "secret-refresh")),
            )
        for (text in texts.map { it.toString() }) assertTrue(text, "secret" !in text)
    }
}
