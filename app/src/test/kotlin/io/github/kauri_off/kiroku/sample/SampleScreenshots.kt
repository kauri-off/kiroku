package io.github.kauri_off.kiroku.sample

import io.github.kauri_off.kiroku.core.fixtures.Fixtures
import io.github.kauri_off.kiroku.screenshots.ScreenshotRegistry
import io.github.kauri_off.kiroku.screenshots.case
import io.github.kauri_off.kiroku.screenshots.contentCase

class SampleScreenshots : ScreenshotRegistry("sample") {
    override val cases =
        listOf(
            contentCase { SampleScreen(SampleFixtures.state, onEvent = {}) },
            case("calibration-noise") { SampleNoiseScreen(SampleFixtures.state, onEvent = {}) },
            case("calibration-hierarchy") { SampleHierarchyScreen(SampleFixtures.state, onEvent = {}) },
        )
}

object SampleFixtures {
    val state =
        SampleState(
            Fixtures.reference.entries.map { entry ->
                SampleRow(
                    id = entry.anime.id,
                    title = entry.anime.titles.romaji,
                    status = entry.status,
                    watched = entry.values.watchedEpisodes,
                    total = entry.anime.malEpisodes,
                )
            },
        )
}
