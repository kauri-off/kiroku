package io.github.kauri_off.kiroku.core.model

enum class ThemeMode { System, Light, Dark, PureBlack }

enum class StartupTab { List, Search, Profile }

data class DisplayPreferences(
    val theme: ThemeMode = ThemeMode.System,
    val startupTab: StartupTab = StartupTab.List,
)
