package io.github.kauri_off.kiroku.screenshots

import android.content.Context
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.test.FakeImageLoaderEngine
import io.github.kauri_off.kiroku.standin.StandInPosters

// A fixture title's poster is its stand-in art; any other remote image is a flat block in a colour no surface role
// uses, with a MAL poster's proportions.
@OptIn(DelicateCoilApi::class)
object RemoteImages {
    const val BLOCK = 0xFF8C8C8C.toInt()
    const val POSTER_WIDTH = 225
    const val POSTER_HEIGHT = 318

    fun install(context: Context) {
        val engine = FakeImageLoaderEngine.Builder().default(ColorImage(BLOCK, width = POSTER_WIDTH, height = POSTER_HEIGHT)).build()
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
