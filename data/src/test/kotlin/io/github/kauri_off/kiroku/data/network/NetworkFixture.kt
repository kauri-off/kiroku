package io.github.kauri_off.kiroku.data.network

import io.github.kauri_off.kiroku.data.recorder.RecordedAnswers
import kotlinx.coroutines.flow.flowOf
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
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ServerSocketFactory
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

// The next request the server received; a test fails, and does not wait, when none arrives.
internal fun MockWebServer.received(): RecordedRequest = checkNotNull(takeRequest(5, TimeUnit.SECONDS)) { "No request arrived" }

// The server writes an answer's head and body apart; without this the body waits for the client's delayed acknowledgement.
private object AnswerAtOnce : ServerSocketFactory() {
    override fun createServerSocket(): ServerSocket =
        object : ServerSocket() {
            override fun accept(): Socket = super.accept().apply { tcpNoDelay = true }
        }

    override fun createServerSocket(port: Int): ServerSocket = createServerSocket(port, 0, null)

    override fun createServerSocket(
        port: Int,
        backlog: Int,
    ): ServerSocket = createServerSocket(port, backlog, null)

    override fun createServerSocket(
        port: Int,
        backlog: Int,
        address: InetAddress?,
    ): ServerSocket = createServerSocket().apply { bind(InetSocketAddress(address, port), backlog) }
}

internal class TestClock(
    var now: Instant = Instant.parse("2026-11-13T12:00:00Z"),
) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

// A back-off file of a client whose back-off no test reads, gone with the tests' process.
internal fun scratchBackOffFile(): File =
    File(Files.createTempDirectory("mal").toFile().apply { deleteOnExit() }, "back-off").apply { deleteOnExit() }

// Two local servers in place of MAL's hosts and a client over them, on a device whose main thread is the test's.
internal class NetworkFixture : ExternalResource() {
    val api = MockWebServer().apply { serverSocketFactory = AnswerAtOnce }
    val signIn = MockWebServer().apply { serverSocketFactory = AnswerAtOnce }
    val clock = TestClock()
    val log = CopyOnWriteArrayList<String>()
    val unexpected = CopyOnWriteArrayList<Throwable>()
    var calls: okhttp3.Dispatcher? = null
    var online = true
    var clientId = CLIENT_ID
    var repeatReadsAfter = listOf(1.milliseconds, 1.milliseconds)
    private val mainThread = Thread.currentThread()
    private val directory by lazy { Files.createTempDirectory("mal").toFile() }

    // Where the client keeps MAL's back-off, as an app keeps it among its files.
    val backOffFile: File get() = File(directory, "back-off")

    val http by lazy { newProcess() }

    // The client a new process makes, over the same file.
    fun newProcess(): MalHttp =
        MalHttp(
            hosts = MalHosts(api = api.url("/v2/").toString(), signIn = signIn.url("/v1/oauth2/").toString()),
            clientId = clientId,
            appVersion = "1.2.3",
            device =
                object : Device {
                    override fun isOnline() = online

                    override fun observeOnline() = flowOf(online)

                    override fun isMainThread() = Thread.currentThread() === mainThread
                },
            clock = clock,
            backOffFile = { backOffFile },
            unexpected = unexpected::add,
            log = log::add,
            repeatReadsAfter = repeatReadsAfter,
            calls = calls,
        )

    val things by lazy { http.api(Things::class) }
    val signInThings by lazy { http.signIn(Things::class) }

    // Every request to the API from now on gets this answer.
    fun always(response: MockResponse) = answers { response }

    fun answers(answer: (RecordedRequest) -> MockResponse) {
        api.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
            }
    }

    override fun before() {
        api.start()
        signIn.start()
    }

    override fun after() {
        api.close()
        signIn.close()
        directory.deleteRecursively()
    }

    companion object {
        const val CLIENT_ID = "the-client-id-0f3a"
        const val TOKEN = "the-token-9c1e"
        const val THING = """{"value":7}"""

        // MAL refusing a request: its recorded error shape, with the code MAL gives for it.
        val MalsRefusal by lazy { RecordedAnswers.named("list_private").body.replace("not_permitted", "forbidden") }

        fun answer(
            code: Int = 200,
            body: String = THING,
            type: String = "application/json; charset=utf-8",
        ): MockResponse =
            MockResponse
                .Builder()
                .code(code)
                .setHeader("Content-Type", type)
                .body(body)
                .build()

        // The request arrives and the connection closes without an answer.
        fun dropped(): MockResponse = MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
    }
}
