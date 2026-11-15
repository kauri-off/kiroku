package io.github.kauri_off.kiroku.data.list

import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.ListEntry
import io.github.kauri_off.kiroku.data.network.AUTHORIZATION
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.MalHttp
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
    data object SignedIn : ListUser

    data class Named(
        val name: String,
    ) : ListUser
}

// A user's whole list from MAL, page after page, or the failure of the first page that failed.
class MalLists(
    private val http: MalHttp,
    private val session: MalSession,
) {
    private val api by http.api(ListApi::class)

    suspend fun list(user: ListUser): Outcome<List<ListEntry>> {
        val entries = mutableListOf<ListEntry>()
        val asked = mutableListOf<Map<String, String>>()
        var query = Asked + ("offset" to "0")
        while (true) {
            asked += query
            val page =
                when (val outcome = page(user, query, asked)) {
                    is Outcome.Failure -> return outcome
                    is Outcome.Success -> outcome.value
                }
            entries += page.entries
            query = page.next ?: return Outcome.Success(entries)
        }
    }

    // The page at MAL's next link, asked the same way; a list that does not end fails at the page that shows it.
    private suspend fun page(
        user: ListUser,
        query: Map<String, String>,
        asked: List<Map<String, String>>,
    ): Outcome<ListPage> {
        suspend fun read(authorization: String?): ListPage {
            val page = api.page(user.path, query, authorization).toListPage()
            val next = page.next?.plus(Asked) ?: return page
            check(next !in asked) { "A page already read" }
            check(asked.size < MAX_PAGES) { "The list does not end" }
            return ListPage(page.entries, next)
        }
        return when (user) {
            ListUser.SignedIn -> session.authorised(http) { read(it) }
            is ListUser.Named -> http.call(anotherUsersList = true) { read(null) }.clientIdOutcome()
        }
    }

    private val ListUser.path: String
        get() =
            when (this) {
                ListUser.SignedIn -> "@me"
                is ListUser.Named -> name
            }

    private companion object {
        // In the titles' order, which no edit changes, with adult titles, so that none leaves the list.
        val Asked = mapOf("fields" to MalFields.LIST_ENTRY, "nsfw" to "true", "sort" to "anime_title", "limit" to "1000")

        // More pages of MAL's largest size than MAL has titles.
        const val MAX_PAGES = 100
    }
}

private interface ListApi {
    @GET("users/{user}/animelist")
    suspend fun page(
        @Path("user") user: String,
        @QueryMap query: Map<String, String>,
        @Header(AUTHORIZATION) authorization: String?,
    ): JsonObject
}
