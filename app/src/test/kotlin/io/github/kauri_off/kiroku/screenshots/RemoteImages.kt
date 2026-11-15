package io.github.kauri_off.kiroku.screenshots

import android.content.Context
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.request.SuccessResult
import coil3.test.FakeImageLoaderEngine
import io.github.kauri_off.kiroku.standin.StandInPosters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

// A fixture title's poster is its stand-in art; any other remote image is a flat block in a colour no surface role
// uses, with a MAL poster's proportions.
@OptIn(DelicateCoilApi::class)
object RemoteImages {
    const val BLOCK = 0xFF8C8C8C.toInt()
    const val POSTER_WIDTH = 225
    const val POSTER_HEIGHT = 318

    fun install(context: Context) {
        val engine =
            FakeImageLoaderEngine
                .Builder()
                .intercept({ it == ArrivingPoster.URL }, ArrivingPoster.answer)
                .default(ColorImage(BLOCK, width = POSTER_WIDTH, height = POSTER_HEIGHT))
                .build()
        SingletonImageLoader.setUnsafe(
            ImageLoader
                .Builder(context)
                .components {
                    add(StandInPosters)
                    add(engine)
                }.build(),
        )
    }

    fun reset() = SingletonImageLoader.reset()
}

// A large poster whose answer waits until [arrived] and then comes as from the network, or with [inMemory] comes at
// once as from memory; its art is a stand-in of another title's, so its arrival shows.
object ArrivingPoster {
    const val URL = "https://example.invalid/kiroku/arriving/large.jpg"
    val arrived = MutableStateFlow(false)

    @Volatile
    var inMemory = false

    internal val answer =
        FakeImageLoaderEngine.OptionalInterceptor { chain ->
            val image = StandInPosters.draw(ART).asImage()
            if (inMemory) {
                SuccessResult(image, chain.request, DataSource.MEMORY)
            } else {
                arrived.first { it }
                SuccessResult(image, chain.request, DataSource.NETWORK)
            }
        }

    private const val ART = 7L
}
