package io.github.kauri_off.kiroku.data.sandbox

import kotlinx.serialization.json.JsonObject
import java.time.Instant

// One entry of the list as MAL keeps it, each value in MAL's own form; a date is absent rather than empty.
data class Listed(
    val status: String,
    val score: Int,
    val watched: Int,
    val startDate: String?,
    val finishDate: String?,
    val updatedAt: Instant,
)

sealed interface Written {
    data class Done(
        val entry: Listed,
    ) : Written

    // MAL's 400: the entry is as it was.
    data object Refused : Written

    // A title MAL does not know.
    data object Unknown : Written
}

// A list MAL gives anyone who asks for it by its user's name.
sealed interface UsersList {
    // In MAL's order by title.
    data class Public(
        val entries: List<Pair<Long, Listed>>,
    ) : UsersList

    data object Private : UsersList
}

// What the sandbox's answers are made from: the titles MAL knows, the list of its signed-in user with MAL's rules for a
// write, and the lists of its other users.
interface MalList {
    // A title as MAL's own page gives it, every field it has; null for a title MAL does not know.
    fun title(id: Long): JsonObject?

    // The whole list in MAL's order by title.
    fun entries(): List<Pair<Long, Listed>>

    // The most entries a page of the list holds, whatever is asked.
    val pageSize: Int get() = Int.MAX_VALUE

    fun entry(id: Long): Listed?

    // A write's form as MAL reads it: the values it names change, the others stay.
    fun write(
        id: Long,
        form: Map<String, String>,
    ): Written

    // False for a title MAL does not know; a title not on the list is removed all the same.
    fun remove(id: Long): Boolean

    // The list of the user [name] names in any letter case, the signed-in user's included; null for a name no user has.
    fun usersList(name: String): UsersList?
}
