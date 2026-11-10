package io.github.kauri_off.kiroku.screenshots

import android.content.Context
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.test.FakeImageLoaderEngine

// Every remote image is a flat block in a colour that no surface role uses, so its bounds stay visible.
@OptIn(DelicateCoilApi::class)
object RemoteImages {
    const val BLOCK = 0xFF8C8C8C.toInt()

    fun install(context: Context) {
        val engine = FakeImageLoaderEngine.Builder().default(ColorImage(BLOCK)).build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    fun reset() = SingletonImageLoader.reset()
}
