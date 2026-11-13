package io.github.kauri_off.kiroku.data.network

import kotlinx.serialization.Serializable
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.rules.ExternalResource
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Query
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

@Serializable
internal data class Thing(
    val value: Int,
)

// An API of this test's own: nothing here is an address or an answer of MAL's.
internal interface Things {
    @GET("thing")
    suspend fun read(): Thing

    @GET("thing")
    suspend fun readAs(
        @Header(AUTHORIZATION) authorization: String,
    ): Thing

    @FormUrlEncoded
    @PATCH("thing")
    suspend fun write(
        @Field("value") value: Int,
    ): Thing

    @DELETE("thing")
    suspend fun remove()

    @FormUrlEncoded
    @POST("exchange")
    suspend fun exchange(
        @Header(AUTHORIZATION) authorization: String,
        @Query("id") idInQuery: String,
        @Query("token") tokenInQuery: String,
        @Field("id") idInBody: String,
        @Field("token") tokenInBody: String,
    ): Thing
}

internal class TestClock(
    var now: Instant = Instant.parse("2026-11-13T12:00:00Z"),
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

// Two local servers in place of MAL's hosts and a client over them, on a device whose main thread is the test's.
internal class NetworkFixture : ExternalResource() {
    val api = MockWebServer()
    val signIn = MockWebServer()
    val clock = TestClock()
    val log = CopyOnWriteArrayList<String>()
    val unexpected = CopyOnWriteArrayList<Throwable>()
    var online = true
    private val mainThread = Thread.currentThread()

    val http by lazy {
        MalHttp(
            hosts = MalHosts(api = api.url("/v2/").toString(), signIn = signIn.url("/v1/oauth2/").toString()),
            clientId = CLIENT_ID,
            appVersion = "1.2.3",
            device =
                object : Device {
                    override fun isOnline() = online

                    override fun isMainThread() = Thread.currentThread() === mainThread
                },
            clock = clock,
            unexpected = unexpected::add,
            log = log::add,
            repeatReadsAfter = listOf(1.milliseconds, 1.milliseconds),
        )
    }
    val things by lazy { http.api(Things::class) }
    val signInThings by lazy { http.signIn(Things::class) }

    // Every request to the API from now on gets this answer.
    fun always(response: MockResponse) {
        api.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = response
            }
    }

    override fun before() {
        api.start()
        signIn.start()
    }

    override fun after() {
        api.close()
        signIn.close()
    }

    companion object {
        const val CLIENT_ID = "the-client-id-0f3a"
        const val TOKEN = "the-token-9c1e"
        const val THING = """{"value":7}"""

        fun answer(
            code: Int = 200,
            body: String = THING,
        ): MockResponse =
            MockResponse
                .Builder()
                .code(code)
                .body(body)
                .build()

        // The request arrives and the connection closes without an answer.
        fun dropped(): MockResponse = MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
    }
}
