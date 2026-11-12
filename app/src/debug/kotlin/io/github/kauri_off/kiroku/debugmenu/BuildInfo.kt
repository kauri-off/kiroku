package io.github.kauri_off.kiroku.debugmenu

import io.github.kauri_off.kiroku.BuildConfig
import io.github.kauri_off.kiroku.notShown
import java.io.Reader
import java.time.Instant
import java.util.Properties

data class Commit(
    val hash: String,
    val dirty: Boolean,
)

// What is running; the commit and the build time are null where the package does not name them.
data class BuildInfo(
    val type: String,
    val version: String,
    val commit: Commit?,
    val built: Instant?,
) {
    companion object {
        // The file the build adds to the root of a debug or preview package.
        val FILE = notShown("build-info.properties")

        fun read(): BuildInfo {
            val stamp =
                BuildInfo::class.java.classLoader
                    ?.getResourceAsStream(FILE)
                    ?.reader()
                    ?.use(::parse) ?: Stamp(null, null)
            return BuildInfo(BuildConfig.BUILD_TYPE, BuildConfig.VERSION_NAME, stamp.commit, stamp.built)
        }

        internal fun parse(reader: Reader): Stamp {
            val properties = Properties().apply { load(reader) }
            val hash = properties.getProperty(notShown("commit")).orEmpty()
            val commit = hash.takeIf { it.isNotBlank() }?.let { Commit(it, properties.getProperty(notShown("dirty")).toBoolean()) }
            val built = runCatching { Instant.parse(properties.getProperty(notShown("built"))) }.getOrNull()
            return Stamp(commit, built)
        }
    }

    internal data class Stamp(
        val commit: Commit?,
        val built: Instant?,
    )
}
