package io.github.kauri_off.kiroku.data.network

// MAL's two hosts, each as the address its paths are added to.
data class MalHosts(
    val api: String,
    val signIn: String,
) {
    init {
        require(api.endsWith("/") && signIn.endsWith("/"))
    }

    companion object {
        val Real = MalHosts(api = "https://api.myanimelist.net/v2/", signIn = "https://myanimelist.net/v1/oauth2/")
    }
}
