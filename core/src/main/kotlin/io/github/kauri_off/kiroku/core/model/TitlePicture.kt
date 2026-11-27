package io.github.kauri_off.kiroku.core.model

data class TitlePicture(
    val medium: String?,
    val large: String?,
) {
    fun sources(): List<String> = listOfNotNull(large, medium).filterNot(String::isBlank).distinct()
}
