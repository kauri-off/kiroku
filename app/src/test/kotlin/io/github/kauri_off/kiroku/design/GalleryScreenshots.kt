package io.github.kauri_off.kiroku.design

import io.github.kauri_off.kiroku.design.gallery.ComponentGallery
import io.github.kauri_off.kiroku.screenshots.ShowcaseRegistry
import io.github.kauri_off.kiroku.screenshots.contentCase

class GalleryScreenshots : ShowcaseRegistry() {
    override val cases = listOf(contentCase("all") { ComponentGallery() })
}
