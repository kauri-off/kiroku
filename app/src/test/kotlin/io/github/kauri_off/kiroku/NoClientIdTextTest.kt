package io.github.kauri_off.kiroku

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.list.ListFailure
import io.github.kauri_off.kiroku.list.toListFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.kauri_off.kiroku.search.message as pageMessage
import io.github.kauri_off.kiroku.title.message as titleMessage

// A read that a build without a client id did not send says so, not that MAL refused or failed.
class NoClientIdTextTest {
    @Test
    fun aReadNotSentForWantOfAClientIdSaysSo() {
        assertEquals(R.string.common_no_client_id, DataError.NoClientId.titleMessage())
        assertEquals(R.string.common_no_client_id, DataError.NoClientId.pageMessage(R.string.common_could_not_refresh))
        assertEquals(ListFailure.NoClientId, DataError.NoClientId.toListFailure())
    }
}
