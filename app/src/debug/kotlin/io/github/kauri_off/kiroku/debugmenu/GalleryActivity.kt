package io.github.kauri_off.kiroku.debugmenu

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import io.github.kauri_off.kiroku.design.gallery.ComponentGallery
import io.github.kauri_off.kiroku.design.theme.setKirokuContent

// The component gallery, opened from the debug menu.
class GalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setKirokuContent { ComponentGallery(onBack = ::finish) }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, GalleryActivity::class.java)
    }
}
