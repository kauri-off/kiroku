package io.github.kauri_off.kiroku.data.signin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RedirectValuesTest {
    private fun values(uri: String) = redirectValues(uri, REDIRECT)

    @Test
    fun theBuildsRedirectGivesItsValuesDecoded() {
        assertEquals(mapOf("code" to "a b/c+d", "state" to "s-1_2"), values("$REDIRECT?code=a%20b%2Fc%2Bd&state=s-1_2"))
        assertEquals(mapOf("code" to "c", "state" to "s"), values("$REDIRECT/?code=c&state=s"))
        assertEquals(mapOf("code" to "c", "state" to "s"), values("$REDIRECT?code=c&state=s#_=_"))
        assertEquals(mapOf("error" to "access_denied", "state" to ""), values("$REDIRECT?error=access_denied&state"))
        assertEquals(emptyMap<String, String>(), values(REDIRECT))
    }

    @Test
    fun aNameGivenTwiceIsNotAValue() {
        assertEquals(mapOf("code" to "c"), values("$REDIRECT?code=c&state=one&state=two"))
        assertEquals(mapOf("state" to "s"), values("$REDIRECT?code=c&state=s&code=c"))
    }

    @Test
    fun anotherAddressOrOneThatCannotBeReadGivesNothing() {
        val others =
            listOf(
                "",
                "io.github.kauri-off.kiroku://oauth?code=c&state=s",
                "io.github.kauri-off.kiroku.debug.x://oauth?code=c&state=s",
                "IO.GITHUB.KAURI-OFF.KIROKU.DEBUG://oauth?code=c&state=s",
                "$REDIRECT.evil?code=c&state=s",
                "$REDIRECT/path?code=c&state=s",
                "$REDIRECT:80?code=c&state=s",
                "https://myanimelist.net/?next=$REDIRECT?code=c&state=s",
                "$REDIRECT?code=%zz&state=s",
                "$REDIRECT?code=c&state=%",
            )
        for (other in others) assertNull(other, values(other))
    }
}
