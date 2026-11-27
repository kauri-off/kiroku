package io.github.kauri_off.kiroku.data.manga

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.core.error.DataException
import io.github.kauri_off.kiroku.core.error.Outcome
import io.github.kauri_off.kiroku.core.model.MangaDetails
import io.github.kauri_off.kiroku.core.model.MangaFormat
import io.github.kauri_off.kiroku.core.model.MangaId
import io.github.kauri_off.kiroku.core.model.MangaPublication
import io.github.kauri_off.kiroku.core.model.SessionOwner
import io.github.kauri_off.kiroku.core.model.TitlePicture
import io.github.kauri_off.kiroku.core.model.Titles
import io.github.kauri_off.kiroku.core.repository.MangaRepository
import io.github.kauri_off.kiroku.core.repository.StoredManga
import io.github.kauri_off.kiroku.data.db.KirokuDatabase
import io.github.kauri_off.kiroku.data.db.MangaRow
import io.github.kauri_off.kiroku.data.db.instantOf
import io.github.kauri_off.kiroku.data.db.picturesOf
import io.github.kauri_off.kiroku.data.db.picturesToColumn
import io.github.kauri_off.kiroku.data.db.readable
import io.github.kauri_off.kiroku.data.db.toColumn
import io.github.kauri_off.kiroku.data.db.transaction
import io.github.kauri_off.kiroku.data.network.MalFields
import io.github.kauri_off.kiroku.data.network.MalFields.PICTURES
import io.github.kauri_off.kiroku.data.network.MalHttp
import io.github.kauri_off.kiroku.data.network.ReadAdmission
import io.github.kauri_off.kiroku.data.network.ReadDeferred
import io.github.kauri_off.kiroku.data.network.RepairedAnswer
import io.github.kauri_off.kiroku.data.network.clientIdOutcome
import io.github.kauri_off.kiroku.data.network.titlePictures
import io.github.kauri_off.kiroku.data.session.MalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.Clock

class MalMangaRepository(
    private val http: MalHttp,
    private val database: suspend () -> KirokuDatabase,
    private val clock: Clock,
    private val session: MalSession,
    private val awaitList: suspend () -> Unit,
) : MangaRepository {
    private val api by http.api(MangaApi::class)

    override fun observeOwner() = session.observeOwner()

    override suspend fun stored(
        id: MangaId,
        owner: SessionOwner,
    ): Outcome<StoredManga?> =
        storage {
            awaitList()
            inSettledOwner(owner) { it.manga().read(id.value)?.toStored(http.unexpected) }
        }

    override suspend fun refresh(
        id: MangaId,
        owner: SessionOwner,
        canDispatch: () -> Boolean,
    ): Outcome<StoredManga>? {
        val ready = storage { inSettledOwner(owner) {} }
        if (ready is Outcome.Failure) {
            if (ready.error == DataError.ListEnded || !session.isCurrentOwner(owner)) return Outcome.Failure(DataError.ListEnded)
            return ready.takeIf { canDispatch() }
        }
        val repaired = mutableSetOf<String>()
        val answer =
            try {
                http
                    .call(firstRead = {
                        when {
                            !session.isCurrentOwner(owner) -> ReadAdmission.OwnerEnded
                            !canDispatch() -> ReadAdmission.Defer
                            else -> ReadAdmission.Send
                        }
                    }) {
                        api.details(id.value, MalFields.MANGA_DETAILS).toManga(id, repaired).toRow(clock.instant().toColumn())
                    }.clientIdOutcome()
            } catch (_: ReadDeferred) {
                currentCoroutineContext().ensureActive()
                return null
            }
        if (answer is Outcome.Failure) {
            if (answer.error == DataError.ListEnded) return answer
            return session.withOwner(owner) { answer } ?: Outcome.Failure(DataError.ListEnded)
        }
        val row = (answer as Outcome.Success).value
        if (repaired.isNotEmpty()) http.unexpected(RepairedAnswer(repaired))
        return storage {
            inSettledOwner(owner) { db ->
                val dao = db.manga()
                val storedPictures = if (PICTURES in repaired && row.pictures == null) dao.read(id.value)?.pictures else null
                dao.save(row.copy(pictures = row.pictures ?: storedPictures))
                checkNotNull(dao.read(id.value)).toStored(http.unexpected)
            }
        }
    }

    private suspend fun <T> inSettledOwner(
        owner: SessionOwner,
        run: suspend (KirokuDatabase) -> T,
    ): Outcome<T> {
        val db = database()
        return session.withOwner(owner) {
            db.transaction {
                if (db.list().state().owner != owner.id?.value) {
                    Outcome.Failure(DataError.ListEnded)
                } else {
                    Outcome.Success(run(db))
                }
            }
        } ?: Outcome.Failure(DataError.ListEnded)
    }

    private inline fun <T> storage(read: () -> Outcome<T>): Outcome<T> =
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: DataException) {
            Outcome.Failure(e.error)
        } catch (e: Exception) {
            http.unexpected(e)
            Outcome.Failure(DataError.StorageError)
        }
}

private interface MangaApi {
    @GET("manga/{id}")
    suspend fun details(
        @Path("id") id: Long,
        @Query("fields") fields: String,
    ): JsonObject
}

internal fun JsonObject.toManga(
    id: MangaId,
    repaired: MutableSet<String>,
): MangaDetails {
    check((get("id") as? JsonPrimitive)?.longOrNull == id.value)

    fun <T : Any> field(
        key: String,
        from: JsonObject = this,
        label: String = key,
        read: (JsonElement) -> T?,
    ): T? {
        val value = from[key]?.takeUnless { it == JsonNull } ?: return null
        return read(value).also { if (it == null) repaired += label }
    }

    fun text(key: String) = field(key, read = ::stringValue)

    fun count(key: String) = field(key) { (it as? JsonPrimitive)?.intOrNull?.takeIf { n -> n >= 0 } }?.takeIf { it > 0 }
    val picture = field("main_picture") { it as? JsonObject }
    val alternatives = field("alternative_titles") { it as? JsonObject }
    val main = text("title")?.takeUnless(String::isBlank)
    val english = alternatives?.let { field("en", it, "alternative_titles.en", ::stringValue) }?.takeUnless(String::isBlank)
    val japanese = alternatives?.let { field("ja", it, "alternative_titles.ja", ::stringValue) }?.takeUnless(String::isBlank)
    if (main == null) repaired += "title"
    val name =
        main ?: english ?: japanese
            ?: (alternatives?.get("synonyms") as? JsonArray)?.firstNotNullOfOrNull { stringValue(it)?.takeUnless(String::isBlank) }
    val cover =
        TitlePicture(
            picture?.let { field("medium", it, "main_picture.medium", ::stringValue) }?.takeUnless(String::isBlank),
            picture?.let { field("large", it, "main_picture.large", ::stringValue) }?.takeUnless(String::isBlank),
        )
    return MangaDetails(
        id = id,
        titles = Titles(requireNotNull(name), english, japanese),
        posterUrl = cover.medium,
        largePosterUrl = cover.large,
        pictures = get(PICTURES).titlePictures(cover) { repaired += PICTURES },
        synopsis = text("synopsis")?.trim()?.takeUnless(String::isEmpty),
        format = formatOf(text("media_type")?.takeUnless(String::isBlank)),
        publication = publicationOf(text("status")?.takeUnless(String::isBlank)),
        volumes = count("num_volumes"),
        chapters = count("num_chapters"),
        meanScore = field("mean") { (it as? JsonPrimitive)?.doubleOrNull?.takeIf { score -> score in 0.0..10.0 } }?.takeIf { it > 0 },
        rank = count("rank"),
        members = field("num_list_users") { (it as? JsonPrimitive)?.intOrNull?.takeIf { n -> n >= 0 } },
    )
}

internal fun MangaDetails.toRow(fetchedAt: Long): MangaRow =
    MangaRow(
        id.value,
        titles.romaji,
        titles.english,
        titles.japanese,
        posterUrl,
        largePosterUrl,
        synopsis,
        (format as? MangaFormat.Unrecognized)?.code ?: formats.entries.firstOrNull { it.value == format }?.key,
        (publication as? MangaPublication.Unrecognized)?.code ?: publications.entries.firstOrNull { it.value == publication }?.key,
        volumes,
        chapters,
        meanScore,
        fetchedAt,
        rank,
        members,
        picturesToColumn(pictures),
    )

private fun stringValue(value: JsonElement): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun MangaRow.toStored(unexpected: (Throwable) -> Unit): StoredManga =
    StoredManga(
        MangaDetails(
            MangaId(id),
            Titles(romaji, english, japanese),
            poster,
            largePoster,
            synopsis,
            formatOf(format),
            publicationOf(publication),
            volumes,
            chapters,
            meanScore,
            rank,
            members,
            readable(unexpected) { picturesOf(pictures) }.orEmpty(),
        ),
        instantOf(fetchedAt),
    )

private fun formatOf(code: String?): MangaFormat? = code?.let { formats[it] ?: MangaFormat.Unrecognized(it) }

private fun publicationOf(code: String?): MangaPublication? = code?.let { publications[it] ?: MangaPublication.Unrecognized(it) }

private val formats =
    mapOf(
        "manga" to MangaFormat.Known.Manga,
        "novel" to MangaFormat.Known.Novel,
        "light_novel" to MangaFormat.Known.LightNovel,
        "one_shot" to MangaFormat.Known.OneShot,
        "doujinshi" to MangaFormat.Known.Doujinshi,
        "manhwa" to MangaFormat.Known.Manhwa,
        "manhua" to MangaFormat.Known.Manhua,
        "oel" to MangaFormat.Known.Oel,
    )

private val publications =
    mapOf(
        "finished" to MangaPublication.Known.Finished,
        "currently_publishing" to MangaPublication.Known.Publishing,
        "not_yet_published" to MangaPublication.Known.Upcoming,
        "on_hiatus" to MangaPublication.Known.OnHiatus,
        "discontinued" to MangaPublication.Known.Discontinued,
    )
