package io.github.kauri_off.kiroku.data.sandbox

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.Clock

// A sandbox keeping its list, tokens and airing in [file]s, its titles read from public answers at its first request;
// [readTenrai] gives one of Tenrai's recorded answers by its file name.
class Sandbox(
    read: (String) -> String,
    readTenrai: (String) -> String,
    poster: (Long) -> String,
    clock: Clock,
    file: (String) -> File,
) {
    private val airing = lazy { SandboxAiring(SandboxTitles.read(read, poster), clock, file(AIRING)) }

    private val site =
        lazy {
            SandboxList(airing.value.titles, clock, file(LIST), starting = { SandboxTitles.startingList(airing.value.pages(), it) })
        }

    val aniList = SandboxAniList({ airing.value }, clock)

    val tenrai = SandboxTenrai(readTenrai)

    val mal = SandboxMal(OnFirstRequest(site, airing), SandboxTokens(clock, file(TOKENS)), aniList, tenrai)

    // The list the sandbox started with and its schedules laid out again, as of now; then AniList answers again, from them.
    fun reset() {
        airing.value.reset()
        aniList.fault = SandboxAniListFault.None
        site.value.reset()
    }

    private companion object {
        const val LIST = "list"
        const val TOKENS = "tokens"
        const val AIRING = "airing"
    }
}

// MAL's titles with the dates and states of the sandbox's airing.
private class OnFirstRequest(
    private val site: Lazy<SandboxList>,
    private val airing: Lazy<SandboxAiring>,
) : MalList {
    override fun title(id: Long): JsonObject? = site.value.title(id)?.let { SandboxUsers.titleOf(id, airing.value::page) }

    override fun entries(): List<Pair<Long, Listed>> = site.value.entries()

    override fun entry(id: Long): Listed? = site.value.entry(id)

    override fun write(
        id: Long,
        form: Map<String, String>,
    ) = site.value.write(id, form)

    override fun remove(id: Long) = site.value.remove(id)

    override fun usersList(name: String) = site.value.usersList(name)
}
