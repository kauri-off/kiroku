package io.github.kauri_off.kiroku.data.list

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.core.model.SignInId
import io.github.kauri_off.kiroku.data.anime.Repairs
import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.RepairedAnswer
import io.github.kauri_off.kiroku.data.network.anotherUsersListError
import io.github.kauri_off.kiroku.data.network.clientIdOutcome
import io.github.kauri_off.kiroku.data.session.MalSession
import io.github.kauri_off.kiroku.data.session.authorised
import kotlinx.serialization.json.JsonObject
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.QueryMap

// Whose list is read: the signed-in user's, as the session, or a named user's public one, with the client id alone.
sealed interface ListUser {
    class SignedIn(
        val session: MalSession,
        val expectedOwner: SignInId? = null,
    ) : ListUser

    data class Named(
        val name: String,
    ) : ListUser
}

// A user's list from MAL, page after page, or the failure of the first page that failed; a page's repairs are
// reported once the whole list is read.
class MalLists internal constructor(
    private val http: MalHttp,
    private val maxEntries: Int,
) {
    constructor(http: MalHttp) : this(http, MAX_ENTRIES)

    private val api by http.api(ListApi::class)

    suspend fun list(user: ListUser): Outcome<List<ListEntry>> {
        val entries = mutableListOf<ListEntry>()
        return pages(user) { entries += it }.map { entries }
    }

    // Each page's entries once its answer is read and closed, the next page asked only when [each] has returned.
    suspend fun pages(
        user: ListUser,
        each: suspend (List<ListEntry>) -> Unit,
    ): Outcome<Unit> {
        if (user is ListUser.Named && !isUserSegment(user.name)) return Outcome.Failure(DataError.UnknownUser)
        val repaired = mutableListOf<Set<String>>()
        var offset = 0
        while (true) {
            val read =
                when (val outcome = page(user, offset)) {
                    is Outcome.Failure -> return outcome
                    is Outcome.Success -> outcome.value
                }
            if (read.repaired.isNotEmpty()) repaired += read.repaired
            each(read.entries)
            offset = read.next ?: break
        }
        for (fields in repaired) http.unexpected(RepairedAnswer(fields))
        return Outcome.Success(Unit)
    }

    // The next page must start where this one ended, below the bound; anything else fails rather than end the list early.
    private suspend fun page(
        user: ListUser,
        offset: Int,
    ): Outcome<ListPage> {
        suspend fun read(authorization: String?): ListPage {
            val page = api.page(user.path, Asked + ("offset" to "$offset"), authorization).toListPage(Repairs())
            val next = page.next ?: return page
            check(page.entries.isNotEmpty() && next == offset + page.entries.size) { "The next page is not after this one" }
            check(next < maxEntries) { "The list does not end" }
            return page
        }
        return when (user) {
            is ListUser.SignedIn -> user.session.authorised(http, user.expectedOwner) { read(it) }
            is ListUser.Named ->
                http.call(listError = { status, body, _ -> anotherUsersListError(status, body) }) { read(null) }.clientIdOutcome()
        }
    }

    private val ListUser.path: String
        get() =
            when (this) {
                is ListUser.SignedIn -> ME
                is ListUser.Named -> name
            }

    private companion object {
        // In the titles' order, which no edit changes, with adult titles, so that none leaves the list.
        val Asked = mapOf("fields" to MalFields.LIST_ENTRY, "nsfw" to "true", "sort" to "anime_title", "limit" to "1000")

        // Several times the titles MAL has: a list cannot hold more, whatever size MAL makes its pages.
        const val MAX_ENTRIES = 100_000
    }
}

// MAL's word for the signed-in user in place of a name.
private const val ME = "@me"

// A name nobody can have is not asked: one that is no segment of an address, or MAL's word for the signed-in user.
internal fun isUserSegment(name: String): Boolean = name != "" && name != "." && name != ".." && !name.equals(ME, ignoreCase = true)

private interface ListApi {
    @GET("users/{user}/animelist")
    suspend fun page(
        @Path("user") user: String,
        @QueryMap query: Map<String, String>,
        @Header(AUTHORIZATION) authorization: String?,
    ): JsonObject
}
