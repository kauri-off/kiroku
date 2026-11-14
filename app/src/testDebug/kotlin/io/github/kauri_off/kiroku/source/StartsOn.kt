package io.github.kauri_off.kiroku.source

import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment

// The app of a test starts on [source], whatever client id the build has; ordered before the rule that starts it.
class StartsOn(
    private val source: DataSource,
) : ExternalResource() {
    override fun before() = check(SourceChoice(RuntimeEnvironment.getApplication()).choose(source))
}
