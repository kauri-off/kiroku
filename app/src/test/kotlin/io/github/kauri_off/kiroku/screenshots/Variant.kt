package io.github.kauri_off.kiroku.screenshots

import io.github.kauri_off.kiroku.design.theme.Seed
import io.github.kauri_off.kiroku.design.theme.ThemeSettings

enum class Variant(
    val id: String,
    val language: String,
    val dark: Boolean,
    val widthDp: Int,
    val heightDp: Int,
    val fontScale: Float,
    val altSeed: Boolean = false,
    val contentOnly: Boolean = false,
) {
    EnLight("en-light", "en", dark = false, widthDp = 411, heightDp = 891, fontScale = 1.0f),
    EnDark("en-dark", "en", dark = true, widthDp = 411, heightDp = 891, fontScale = 1.0f),
    RuLight("ru-light", "ru", dark = false, widthDp = 411, heightDp = 891, fontScale = 1.0f),
    RuDark("ru-dark", "ru", dark = true, widthDp = 411, heightDp = 891, fontScale = 1.0f),
    RuStress("ru-stress", "ru", dark = false, widthDp = 360, heightDp = 640, fontScale = 1.3f),
    RuLarge("ru-large", "ru", dark = false, widthDp = 411, heightDp = 891, fontScale = 2.0f, contentOnly = true),
    EnAlt("en-alt", "en", dark = false, widthDp = 411, heightDp = 891, fontScale = 1.0f, altSeed = true, contentOnly = true),
    ;

    // The app's own theme settings with dynamic colour off; light or dark comes from the device, as in the app.
    val themeSettings: ThemeSettings get() = ThemeSettings(dynamicColor = false)

    val seed: Seed get() = if (altSeed) Seed.Amber else Seed.Teal

    fun qualifiers(heightDp: Int = this.heightDp): String =
        "$language-w${widthDp}dp-h${heightDp}dp-${if (dark) "night" else "notnight"}-xhdpi"

    companion object {
        fun forCase(isContent: Boolean): List<Variant> = entries.filter { isContent || !it.contentOnly }
    }
}
