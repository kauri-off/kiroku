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

    private val sanitiser =
        Sanitiser(mapOf("the-client-id-0f3a" to "client-id"), names = mapOf("Some_Name-7" to "public-user", "" to "private-user"))

    @Test
    fun `resolving a redacted JSON key collision does not restore the credential`() {
        val answer =
            Sanitiser(mapOf("access-token-2" to "access-token"))
                .privateAnswer("private", 200, """{"access-token-2":12,"access-token":34}""")

        assertEquals("""{"access-token-3":12,"access-token":34}""", answer.text)
    }

    @Test
    fun `a user's name is replaced where it names the user, and the client id wherever an answer repeats it`() {
        val planted =
            """
            {"data":[{"node":{"id":1,"title":"Some_Name-7 no Bouken","studios":[{"name":"SOME_NAME-7"}]}}],
            "echo":{"id":"x the-client-id-0f3a x"},
            "paging":{"next":"https:\/\/api.myanimelist.net\/v2\/users\/SOME_NAME-7\/animelist?offset=10&fields=id",
            "previous":"https://api.myanimelist.net/v2/users/some_name-7"}}
            """.trimIndent()

        val cleaned = sanitiser.json(planted)!!

        assertFalse(cleaned.contains("the-client-id-0f3a", ignoreCase = true))
        val answer = Json.parseToJsonElement(cleaned).jsonObject
        assertEquals(
            """{"next":"https://api.myanimelist.net/v2/users/public-user/animelist?offset=10&fields=id",""" +
                """"previous":"https://api.myanimelist.net/v2/users/public-user"}""",
            "${answer["paging"]}",
        )
        assertEquals("""{"id":"x client-id x"}""", "${answer["echo"]}")
        assertTrue(""""name":"public-user"""" in "$answer")
        // A title that holds the name is MAL's own text and stays whole.
        assertTrue(""""title":"Some_Name-7 no Bouken"""" in "$answer")
    }

    @Test
    fun `a title that holds a user's name survives, whatever the name`() {
        val bebop = Sanitiser(emptyMap(), names = mapOf("Bebop" to "owner-user", "an" to "other-user"))
        val planted = """{"title":"Cowboy Bebop","synopsis":"Humans and titans","name":"bebop","next":"users/an/animelist?q=users/and"}"""

        assertEquals(
            """{"title":"Cowboy Bebop","synopsis":"Humans and titans","name":"owner-user","next":"users/other-user/animelist?q=users/and"}""",
            "${Json.parseToJsonElement(bebop.json(planted)!!)}",
        )
    }

    @Test
    fun `a moment is stored in UTC without a part of a second, wherever an answer holds one`() {
        val moments = """{"updated_at":"2026-11-14T21:00:00.123+09:00","created_at":"2026-11-14T12:00:00Z","""
        val planted = """$moments"day":"2026-11-14","at":"2026-11-14T12:00:00"}"""

        assertEquals(
            """{"updated_at":"2026-11-14T12:00:00+00:00","created_at":"2026-11-14T12:00:00+00:00","day":"2026-11-14","at":"2026-11-14T12:00:00"}""",
            "${Json.parseToJsonElement(sanitiser.json(planted)!!)}",
        )
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
    fun `secrets that overlap or touch are replaced as one, and nothing of either is left`() {
        val names = Sanitiser(mapOf("abcd" to "public-user", "cdef" to "private-user"))
        val nameAndId = Sanitiser(mapOf("kauri_ab" to "public-user", "abcdef0123456789abcdef0123456789" to "client-id"))

        assertEquals("users/public-user/x", names.plain("users/abcdef/x"))
        assertEquals("a public-user, b", names.plain("a ABCDcdef, b"))
        assertEquals("private-user-public-user", names.plain("cdef-abcd"))
        assertEquals("id public-user.", nameAndId.plain("id kauri_abcdef0123456789abcdef0123456789."))
        assertEquals("""{"next":"users/public-user/x"}""", "${Json.parseToJsonElement(names.json("""{"next":"users/abcdef/x"}""")!!)}")
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
    fun `the line that names a text's source is kept whole only while it is short`() {
        val tooLong = "(Source: ${"a company ".repeat(12).trim()})"

        val answer = cleaned("${buildJsonObject { put("synopsis", "Short.\n\n$tooLong") }}")

        assertEquals(
            "Short.\n\n(Source: a company a company a company a company a company…",
            answer["synopsis"]!!.jsonPrimitive.content,
        )
        assertTrue(tooLong.length > SOURCE_LIMIT)
        assertFalse(isClosingSource(tooLong))
        assertTrue(isClosingSource("(Source: Crunchyroll)") && isClosingSource("[Written by MAL Rewrite]"))
        assertFalse(isClosingSource("Prose that ends with its source (Source: Crunchyroll)"))
    }

    @Test
    fun `a name where a paragraph is cut leaves no part of itself`() {
        val start = "https://myanimelist.net/profile/".padEnd(FREE_TEXT_LIMIT - 5, 'a')

        val answer = cleaned("${buildJsonObject { put("synopsis", "${start}the-client-id-0f3a/and/on/and/on/and/on") }}")

        assertEquals("${start}clie…", answer["synopsis"]!!.jsonPrimitive.content)
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
                """"updated_at":"2020-01-15T15:23:53+00:00","start_date":"2020-01-08","finish_date":"2020-01",""" +
                """"comments":"","tags":[],"priority":0,"num_times_rewatched":0,"rewatch_value":0,"something_new":""}""",
            "${entries[0]["list_status"]}",
        )
        assertEquals(
            """{"status":"plan_to_watch","score":0,"num_episodes_watched":0,"is_rewatching":false,"updated_at":"2022-01-01T17:35:52+00:00"}""",
            "${entries[1]["list_status"]}",
        )
        assertEquals(
            """{"status":"watching","score":0,"num_episodes_watched":3,"is_rewatching":false,""" +
                """"updated_at":"2020-01-10T19:47:51+00:00","start_date":"2020"}""",
            "${entries[2]["list_status"]}",
        )
        assertEquals("""{"id":7,"title":"A","mean":8.5}""", "${entries[0]["node"]}")
        assertEquals(sanitiser.json(planted), sanitiser.json(sanitiser.json(planted)!!))
    }

    @Test
    fun `a made-up start date is never before the day the title itself started`() {
        val planted =
            """
            {"data":[
            {"node":{"id":7,"start_date":"2024-10-05"},"list_status":{"status":"completed","start_date":"2024-10-06","finish_date":"2024-12-28"}},
            {"node":{"id":8,"start_date":"2019-04"},"list_status":{"status":"watching","start_date":"2019"}},
            {"node":{"id":9,"start_date":"2031"},"list_status":{"status":"watching","start_date":"2031-02-03"}},
            {"node":{"id":10,"start_date":"soon"},"list_status":{"status":"watching","start_date":"2031-02-03"}}
            ]}
            """.trimIndent()

        val values = cleaned(planted)["data"]!!.jsonArray.map { "${it.jsonObject["list_status"]}" }

        assertEquals(
            listOf(
                """{"status":"completed","start_date":"2024-10-12","finish_date":"2024-10-19"}""",
                """{"status":"watching","start_date":"2019"}""",
                """{"status":"watching","start_date":"2026-11-01"}""",
                """{"status":"watching","start_date":"2020-01-11"}""",
            ),
            values,
        )
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
    fun `stored answers are brought up to the rules, and one that follows them or a signed-in one is left as it is`() {
        val long = "word ".repeat(40).trim()
        File(folder.root, "title.200.json").writeText("""{"id":1,"synopsis":"$long"}""")
        File(folder.root, "page.404.txt").writeText("<html>Not found</html>")
        val following = File(folder.root, "other.200.json").apply { writeText(sanitiser.json("""{"id":5}""")!!) }
        val written = following.lastModified()
        // A signed-in answer is kept as MAL sent it, whatever it holds.
        val asSent = """{"id":5,"synopsis":"$long","my_list_status":{"status":"completed","score":7}}"""
        val readBack = File(folder.root, "score_set_read.200.json").apply { writeText(asSent) }
        val another = File(folder.root, "title_other.200.json").apply { writeText(asSent) }

        assertEquals(listOf("title.200.json", "title_other.200.json"), sanitiseStored(folder.root))

        assertEquals(asSent, readBack.readText())
        assertFalse(another.readText(), "\"score\": 7" in another.readText())

        assertFalse(long in File(folder.root, "title.200.json").readText())
        assertEquals(written, following.lastModified())
        assertEquals(emptyList<String>(), sanitiseStored(folder.root))
    }
}
