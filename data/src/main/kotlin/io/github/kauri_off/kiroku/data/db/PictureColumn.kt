package io.github.kauri_off.kiroku.data.db

import io.github.kauri_off.kiroku.core.model.TitlePicture
import kotlinx.serialization.json.Json

internal fun picturesToColumn(pictures: List<TitlePicture>): String? =
    pictures.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it.map { picture -> listOf(picture.medium, picture.large) }) }

internal fun picturesOf(column: String?): List<TitlePicture> =
    column?.let { Json.decodeFromString<List<List<String?>>>(it).map { pair -> TitlePicture(pair[0], pair[1]) } }.orEmpty()
