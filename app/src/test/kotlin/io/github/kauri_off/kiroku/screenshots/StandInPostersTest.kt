package io.github.kauri_off.kiroku.screenshots

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.kauri_off.kiroku.core.fixtures.FixtureAnime
import io.github.kauri_off.kiroku.design.component.Poster
import io.github.kauri_off.kiroku.design.component.PosterSize
import io.github.kauri_off.kiroku.standin.StandInPosters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

// A fixture title's poster is drawn art shown through Poster; a title without one keeps the placeholder.
@RunWith(ScreenshotRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StandInPostersTest {
    @Test
    fun aFixturePosterIsDrawnArtThroughThePosterComponent() {
        val drawn = centre(FixtureAnime.exactToWatch.posterUrl)
        val placeholder = centre(null)
        assertNotEquals(placeholder, drawn)
        assertNotEquals(RemoteImages.BLOCK, drawn)
        assertEquals(RemoteImages.BLOCK, centre("https://cdn.myanimelist.net/images/anime/1/1.jpg"))
    }

    @Test
    fun eachTitleGetsItsOwnArtTheSameEveryTime() {
        val a = StandInPosters.draw(FixtureAnime.exactToWatch.id.value)
        val b = StandInPosters.draw(FixtureAnime.longTitle.id.value)
        assertFalse(a.sameAs(b))
        assertTrue(a.sameAs(StandInPosters.draw(FixtureAnime.exactToWatch.id.value)))
        assertEquals(StandInPosters.WIDTH to StandInPosters.HEIGHT, a.width to a.height)
    }

    @Test
    fun onlyAFixturePosterAddressIsAnswered() {
        assertEquals(FixtureAnime.exactToWatch.id.value, FixtureAnime.posterId(FixtureAnime.exactToWatch.posterUrl!!))
        assertNull(FixtureAnime.posterId("https://cdn.myanimelist.net/images/anime/1/1.jpg"))
    }

    private fun centre(url: String?): Int {
        val image: Bitmap =
            Shot
                .launch(Variant.EnLight) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.Center) { Poster(url, PosterSize.Header) }
                    }
                }.use { it.image() }
        return image.getPixel(image.width / 2, image.height / 2)
    }
}
