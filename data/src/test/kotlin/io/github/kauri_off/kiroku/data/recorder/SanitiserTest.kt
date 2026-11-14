package io.github.kauri_off.kiroku.data.recorder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Section 11.1: no stored answer holds a user's name, a user's values, the client id or somebody's prose.
class SanitiserTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sanitiser = Sanitiser(mapOf("Some_Name-7" to "public-user", "" to "private-user", "the-client-id-0f3a" to "client-id"))

    @Test
    fun `a user's name and the client id are replaced wherever an answer repeats them`() {
        val planted =
            """
            {"data":[{"node":{"id":1,"title":"A title","studios":[{"name":"by some_name-7"}]}}],
            "some_name-7":{"id":"the-client-id-0f3a"},
            "paging":{"next":"https:\/\/api.myanimelist.net\/v2\/users\/SOME_NAME-7\/animelist?offset=10&fields=id"}}
            """.trimIndent()

        val cleaned = sanitiser.json(planted)!!

        assertFalse(cleaned.contains("some_name-7", ignoreCase = true))
        assertFalse(cleaned.contains("the-client-id-0f3a", ignoreCase = true))
        val answer = Json.parseToJsonElement(cleaned).jsonObject
        assertEquals(
            """"https://api.myanimelist.net/v2/users/public-user/animelist?offset=10&fields=id"""",
            "${answer["paging"]!!.jsonObject["next"]}",
        )
        assertEquals("""{"id":"client-id"}""", "${answer["public-user"]}")
        assertTrue(""""name":"by public-user"""" in "$answer")
        assertTrue(""""title":"A title"""" in "$answer")
    }

    @Test
    fun `a page keeps its first ten entries and everything else of the answer`() {
        val related = (1..12).joinToString(",") { """{"node":{"id":$it}}""" }
        val entries = (1..25).joinToString(",") { """{"node":{"id":$it,"mean":8.5,"related_anime":[$related]}}""" }

        val answer =
            Json
                .parseToJsonElement(
                    sanitiser.json("""{"data":[$entries],"paging":{"next":"more"},"season":{"year":2026}}""")!!,
                ).jsonObject

        assertEquals((1..10).map { "$it" }, answer["data"]!!.jsonArray.map { "${it.jsonObject["node"]!!.jsonObject["id"]}" })
        assertEquals(
            12,
            answer["data"]!!
                .jsonArray[0]
                .jsonObject["node"]!!
                .jsonObject["related_anime"]!!
                .jsonArray.size,
        )
        assertEquals("8.5", "${answer["data"]!!.jsonArray[0].jsonObject["node"]!!.jsonObject["mean"]}")
        assertEquals("""{"next":"more"}""", "${answer["paging"]}")
        assertEquals("""{"year":2026}""", "${answer["season"]}")
    }

    @Test
    fun `a secret that holds another is replaced whole, whichever is named first`() {
        val publicInPrivate = Sanitiser(mapOf("kauri" to "public-user", "kauri_alt" to "private-user", "0f3a" to "client-id"))
        val privateInPublic = Sanitiser(mapOf("kauri_main" to "public-user", "Kauri" to "private-user", "0f3a" to "client-id"))
        val nameInClientId = Sanitiser(mapOf("ab12" to "public-user", "other" to "private-user", "ffab12ee99" to "client-id"))
        val clientIdInName = Sanitiser(mapOf("0f3a" to "client-id", "x0f3ay" to "public-user"))

        assertEquals("users/private-user/list, users/public-user/list", publicInPrivate.plain("users/kauri_alt/list, users/KAURI/list"))
        assertEquals("users/public-user/list, users/private-user/list", privateInPublic.plain("users/kauri_main/list, users/kauri/list"))
        assertEquals("id client-id, name public-user", nameInClientId.plain("id ffab12ee99, name ab12"))
        assertEquals("name public-user, id client-id", clientIdInName.plain("name x0f3ay, id 0f3a"))
        assertEquals(
            """{"next":"users/private-user/list"}""",
            "${Json.parseToJsonElement(publicInPrivate.json("""{"next":"users/kauri_alt/list"}""")!!)}",
        )
    }

    @Test
    fun `a name MAL writes with escapes is replaced`() {
        val cleaned = sanitiser.json("""{"name":"\u0053ome_Name-7","link":"users\/Some_Name\u002d7\/animelist"}""")!!

        assertFalse(cleaned.contains("some_name", ignoreCase = true))
        assertEquals("""{"name":"public-user","link":"users/public-user/animelist"}""", "${Json.parseToJsonElement(cleaned)}")
    }

    @Test
    fun `a body that is not a JSON object or array is not stored`() {
        assertNull(sanitiser.json("<html>Not found: Some%5FName&#45;7</html>"))
        assertNull(sanitiser.json(""))
        assertNull(sanitiser.json(""""Some_Name-7""""))
        assertEquals("[\n    \"public-user\"\n]\n", sanitiser.json("""["Some_Name-7"]"""))
    }

    private fun cleaned(planted: String): JsonObject = Json.parseToJsonElement(sanitiser.json(planted)!!).jsonObject

    @Test
    fun `a text that is somebody's prose keeps its line breaks, its closing source and the start of each paragraph`() {
        val long = "Every word of this paragraph is somebody's prose and far longer than a stored paragraph may be."
        val cut = "Every word of this paragraph is somebody's prose and far…"
        val source = "(Source: a company whose name alone is longer than any paragraph that is kept)"
        val planted =
            buildJsonObject {
                put("synopsis", "$long\n\n$long\r\nA short one.\n\n[Written by MAL Rewrite]")
                put("background", "$source\n$long\n$source\n")
                putJsonObject("node") {
                    put("synopsis", "$long\n\n$long")
                    put("background", "")
                    put("title", long)
                }
                putJsonObject("other") { put("synopsis", JsonNull) }
            }

        val answer = cleaned("$planted")

        assertEquals("$cut\n\n$cut\r\nA short one.\n\n[Written by MAL Rewrite]", answer["synopsis"]!!.jsonPrimitive.content)
        assertEquals(
            "(Source: a company whose name alone is longer than any…\n$cut\n$source\n",
            answer["background"]!!.jsonPrimitive.content,
        )
        assertEquals("$cut\n\n$cut", answer["node"]!!.jsonObject["synopsis"]!!.jsonPrimitive.content)
        assertEquals("", answer["node"]!!.jsonObject["background"]!!.jsonPrimitive.content)
        assertEquals(long, answer["node"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, answer["other"]!!.jsonObject["synopsis"])
        assertTrue(cut.length <= FREE_TEXT_LIMIT)
        assertEquals(sanitiser.json("$planted"), sanitiser.json(sanitiser.json("$planted")!!))
    }

    @Test
    fun `a name where a paragraph is cut leaves no part of itself`() {
        val start = "https://myanimelist.net/profile/".padEnd(FREE_TEXT_LIMIT - 5, 'a')

        val answer = cleaned("${buildJsonObject { put("synopsis", "${start}Some_Name-7/and/on/and/on/and/on") }}")

        assertEquals("${start}publ…", answer["synopsis"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a paragraph is measured and cut in characters`() {
        val face = "\uD83D\uDE00"
        val planted = buildJsonObject { put("synopsis", "${face.repeat(FREE_TEXT_LIMIT + 10)}\n${face.repeat(FREE_TEXT_LIMIT)}") }

        val answer = cleaned("$planted")

        assertEquals(
            "${face.repeat(FREE_TEXT_LIMIT - 1)}…\n${face.repeat(FREE_TEXT_LIMIT)}",
            answer["synopsis"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `an entry of a list keeps its status and its count, and every other value of the user's is made up in the same shape`() {
        val planted =
            """
            {"data":[
            {"node":{"id":7,"title":"A","mean":8.5},"list_status":{"status":"completed","score":9,"num_episodes_watched":12,
            "is_rewatching":true,"updated_at":"2015-05-27T23:48:28+09:00","start_date":"2014-03-02","finish_date":"2014-04",
            "comments":"a note of mine","tags":["mine"],"priority":2,"num_times_rewatched":3,"rewatch_value":4,"something_new":"mine"}},
            {"node":{"id":8,"title":"B"},"list_status":{"status":"plan_to_watch","score":0,"num_episodes_watched":0,
            "is_rewatching":false,"updated_at":"2016-01-02T03:04:05+00:00"}},
            {"node":{"id":9,"title":"C"},"list_status":{"status":"watching","score":0,"num_episodes_watched":3,
            "is_rewatching":false,"updated_at":"2016-01-02T03:04:05+00:00","start_date":"2019"}}
            ],"paging":{}}
            """.trimIndent()

        val entries = cleaned(planted)["data"]!!.jsonArray.map { it.jsonObject }

        assertEquals(
            """{"status":"completed","score":8,"num_episodes_watched":12,"is_rewatching":false,""" +
                """"updated_at":"2022-01-01T15:23:53+09:00","start_date":"2020-01-08","finish_date":"2020-01",""" +
                """"comments":"","tags":[],"priority":0,"num_times_rewatched":0,"rewatch_value":0,"something_new":""}""",
            "${entries[0]["list_status"]}",
        )
        assertEquals(
            """{"status":"plan_to_watch","score":0,"num_episodes_watched":0,"is_rewatching":false,"updated_at":"2022-01-01T17:35:52+00:00"}""",
            "${entries[1]["list_status"]}",
        )
        assertEquals(
            """{"status":"watching","score":0,"num_episodes_watched":3,"is_rewatching":false,""" +
                """"updated_at":"2022-01-01T19:47:51+00:00","start_date":"2020"}""",
            "${entries[2]["list_status"]}",
        )
        assertEquals("""{"id":7,"title":"A","mean":8.5}""", "${entries[0]["node"]}")
        assertEquals(sanitiser.json(planted), sanitiser.json(sanitiser.json(planted)!!))
    }

    @Test
    fun `the links of a page are those of a page of ten entries`() {
        val list = "https://api.myanimelist.net/v2/users/public-user/animelist"
        val ranking = "https://api.myanimelist.net/v2/anime/ranking?offset=10&ranking_type=airing&limit=10&fields=id%2Ctitle"
        val planted =
            """{"data":[],"paging":{"previous":"$list?offset=321&fields=id%2Ctitle&nsfw=true&limit=321","next":"$list?offset=963&fields=id%2Ctitle&nsfw=true&limit=321"}}"""

        val links = cleaned(planted)["paging"]!!.jsonObject
        val asAsked = cleaned("""{"data":[],"paging":{"next":"$ranking"}}""")["paging"]!!.jsonObject

        assertEquals("$list?offset=10&fields=id%2Ctitle&nsfw=true&limit=10", links["previous"]!!.jsonPrimitive.content)
        assertEquals("$list?offset=30&fields=id%2Ctitle&nsfw=true&limit=10", links["next"]!!.jsonPrimitive.content)
        assertEquals(ranking, asAsked["next"]!!.jsonPrimitive.content)
        assertNull(cleaned("""{"data":[]}""")["paging"])
    }

    @Test
    fun `stored answers are brought up to the rules, and one that follows them is left as it is`() {
        val long = "word ".repeat(40).trim()
        File(folder.root, "title.200.json").writeText("""{"id":1,"synopsis":"$long"}""")
        File(folder.root, "page.404.txt").writeText("<html>Not found</html>")
        val following = File(folder.root, "other.200.json").apply { writeText(sanitiser.json("""{"id":5}""")!!) }
        val written = following.lastModified()

        assertEquals(listOf("title.200.json"), sanitiseStored(folder.root))

        assertFalse(long in File(folder.root, "title.200.json").readText())
        assertEquals(written, following.lastModified())
        assertEquals(emptyList<String>(), sanitiseStored(folder.root))
    }
}
