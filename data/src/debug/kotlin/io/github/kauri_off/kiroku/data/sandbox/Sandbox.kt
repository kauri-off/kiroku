package io.github.kauri_off.kiroku.data.sandbox

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.Clock

// A sandbox keeping its list and tokens in [file]s, its titles read from public answers at its first request.
class Sandbox(
    read: (String) -> String,
    poster: (Long) -> String,
    clock: Clock,
    file: (String) -> File,
) {
    private val site = lazy { SandboxList(SandboxTitles.read(read, poster), clock, file(LIST)) }

    val mal = SandboxMal(OnFirstRequest(site), SandboxTokens(clock, file(TOKENS)))

    // The list the sandbox started with, as of now.
    fun reset() = site.value.reset()

    private companion object {
        const val LIST = "list"
        const val TOKENS = "tokens"
    }
}

private class OnFirstRequest(
    private val site: Lazy<SandboxList>,
) : MalList {
    override fun title(id: Long): JsonObject? = site.value.title(id)

    override fun entries(): List<Pair<Long, Listed>> = site.value.entries()

    override fun entry(id: Long): Listed? = site.value.entry(id)

    override fun write(
        id: Long,
        form: Map<String, String>,
    ) = site.value.write(id, form)

    override fun remove(id: Long) = site.value.remove(id)
}
