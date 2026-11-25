package io.github.kauri_off.kiroku.data.network

import io.github.kauri_off.kiroku.core.error.DataError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.reflect.Type
import java.time.Clock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import java.time.Duration as JavaDuration

// The one HTTP client for MAL (section 6.4): built on its first use, which fails on the main thread.
class MalHttp(
    internal val hosts: MalHosts,
    internal val clientId: String,
    appVersion: String,
    private val device: Device,
    private val clock: Clock,
    // Where MAL's back-off is kept across a process's death, asked at its first use; one client, and one file, per process.
    backOffFile: () -> File,
    // Hears of an answer that could not be used and of a fault of this layer, each without its message.
    internal val unexpected: (Throwable) -> Unit,
    log: ((String) -> Unit)? = null,
    private val repeatReadsAfter: List<Duration> = RepeatReadsAfter,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // Answers every request in MAL's place on the device itself, so none reaches a host; only debug builds have one.
    private val standIn: Interceptor? = null,
    // Where OkHttp runs a call; a test's may run it on the caller's own thread.
    private val calls: Dispatcher? = null,
) {
    internal val backOff by lazy { MalBackOff(MalBackOff.file(backOffFile(), clock, unexpected)) }

    // How long work without a screen leaves MAL alone yet; zero when it may call.
    internal suspend fun refusedFor(): JavaDuration = withContext(io) { backOff.remaining() }

    // False when the device answers in MAL's place, as the sandbox does, also for titles MAL does not have.
    internal val reachesMal: Boolean get() = standIn == null

    private val client: OkHttpClient by lazy {
        offMainThread()
        OkHttpClient
            .Builder()
            .connectTimeout(ConnectTimeout.toJavaDuration())
            .readTimeout(TransferTimeout.toJavaDuration())
            .writeTimeout(TransferTimeout.toJavaDuration())
            .addInterceptor(UserAgent("Kiroku/$appVersion"))
            .addInterceptor(MarkSends)
            .apply { if (log != null) addInterceptor(RequestLog(log)) }
            .addInterceptor(WithoutClientId(hosts.api.toHttpUrl(), clientId))
            .apply { if (standIn != null) addInterceptor(standIn) }
            .apply { if (calls != null) dispatcher(calls) }
            .addNetworkInterceptor(SendLimits)
            .addNetworkInterceptor(ClientId(hosts.api.toHttpUrl(), clientId))
            .build()
    }

    // The sends of the call this thread is running, for the requests it makes.
    private val calling = ThreadLocal<Sends?>()

    // For the image loader, which asks off the main thread.
    val callFactory: Call.Factory =
        Call.Factory { request ->
            val sends = calling.get()
            client.newCall(if (sends == null) request else request.newBuilder().tag(Sends::class.java, sends).build())
        }

    private val apiServices by lazy { services(hosts.api) }
    private val signInServices by lazy { services(hosts.signIn) }

    // A Retrofit service of MAL's API, made on its first use; its methods return the body and are called in [call].
    internal fun <S : Any> api(service: KClass<S>): Lazy<S> = lazy { apiServices.create(service.java) }

    internal fun <S : Any> signIn(service: KClass<S>): Lazy<S> = lazy { signInServices.create(service.java) }

    // Runs [request] off the main thread; however it fails, the failure is an answer: only an Error is not caught.
    internal suspend fun <T> call(
        // MAL's answers for a private list and an unknown name are then those outcomes, and no reason to back off.
        anotherUsersList: Boolean = false,
        maySendRead: (() -> Boolean)? = null,
        request: suspend () -> T,
    ): MalAnswer<T> {
        val sends = Sends(limit = 1 + repeatReadsAfter.size, maySendRead = maySendRead)
        return withContext(io + calling.asContextElement(sends)) { asked(sends, anotherUsersList, request) }
    }

    private suspend fun <T> asked(
        sends: Sends,
        anotherUsersList: Boolean,
        request: suspend () -> T,
    ): MalAnswer<T> {
        var tries = 0
        while (true) {
            val tried = once(anotherUsersList, request)
            val wait = repeatReadsAfter.getOrNull(tries++)
            if (!tried.again || wait == null || sends.wrote || sends.spent) return tried.answer
            currentCoroutineContext().ensureActive()
            delay(wait)
        }
    }

    // [again]: a read that ended so may be asked once more, when no answer came or the server failed.
    private class Tried<out T>(
        val answer: MalAnswer<T>,
        val again: Boolean = false,
    )

    private suspend fun <T> once(
        anotherUsersList: Boolean,
        request: suspend () -> T,
    ): Tried<T> {
        val mark = backOff.mark()
        return try {
            Tried(MalAnswer.Success(request()).also { backOff.answered(mark) })
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            unusable(e)
        } catch (e: HttpException) {
            val body = if (anotherUsersList || e.code() == 403) e.response()?.errorBody()?.let(::text) else null
            answered(e.code(), e.response()?.headers(), body, anotherUsersList)
        } catch (_: ReadOwnerEnded) {
            Tried(MalAnswer.Failed(DataError.ListEnded))
        } catch (e: NotSentAgain) {
            e.status?.let { answered(it, null, body = null, anotherUsersList = false) } ?: unanswered(e.failure)
        } catch (_: NoClientId) {
            Tried(MalAnswer.Failed(DataError.NoClientId))
        } catch (_: NotAnAnswer) {
            Tried(MalAnswer.Failed(DataError.ServerError))
        } catch (e: IOException) {
            unanswered(e)
        } catch (e: Exception) {
            unusable(e)
        }
    }

    // A body MAL sent with a failure; null when it cannot be read.
    private fun text(body: ResponseBody): String? =
        try {
            body.string()
        } catch (_: IOException) {
            null
        }

    private fun answered(
        status: Int,
        headers: Headers?,
        // The failure's body, when it was read.
        body: String?,
        anotherUsersList: Boolean,
    ): Tried<Nothing> {
        // A 403 MAL did not write is the device's network (a portal, a proxy, a block page), not MAL refusing the app.
        val notMals = status == 403 && body?.let(::errorCode) == null
        val ofAnotherUsersList = if (anotherUsersList && body != null) anotherUsersListError(status, body) else null
        val error =
            when {
                ofAnotherUsersList != null -> ofAnotherUsersList
                status == 401 -> return Tried(MalAnswer.Rejected)
                notMals -> DataError.ServerError
                status == 403 || status == 429 -> DataError.Refused
                status == 404 -> DataError.NotFound
                status == 408 || status >= 500 -> DataError.ServerError
                status >= 400 -> DataError.InvalidRequest
                else -> DataError.ServerError
            }
        if (error == DataError.Refused) backOff.refused(headers?.let { retryAfter(it, clock) }?.toJavaDuration())
        return Tried(MalAnswer.Failed(error), again = status >= 500 || notMals)
    }

    // Not asked again after a timeout, which the user has waited out, or offline.
    private fun unanswered(e: IOException?): Tried<Nothing> {
        val offline = offline()
        return Tried(
            MalAnswer.Failed(if (offline) DataError.NoConnection else DataError.ServerError),
            again = !offline && e !is InterruptedIOException,
        )
    }

    // A 2xx without the body its method needs is not MAL's and not reported; any other fault here is.
    private fun unusable(e: Exception): Tried<Nothing> {
        if (e !is KotlinNullPointerException) unexpected(UnusableAnswer(e))
        return Tried(MalAnswer.Failed(DataError.ServerError))
    }

    // A request to a source other than MAL over this client, sent once: MAL's repeats and back-off have no part in it.
    internal suspend fun send(request: Request): Sent =
        withContext(io) {
            try {
                val call = callFactory.newCall(request)
                suspendCancellableCoroutine { sent ->
                    sent.invokeOnCancellation { call.cancel() }
                    call.enqueue(
                        object : Callback {
                            override fun onFailure(
                                call: Call,
                                e: IOException,
                            ) = sent.resumeWithException(e)

                            override fun onResponse(
                                call: Call,
                                response: Response,
                            ) {
                                val answer =
                                    try {
                                        response.use { Sent.Answered(it.code, it.headers, it.body.string()) }
                                    } catch (e: IOException) {
                                        return sent.resumeWithException(e)
                                    }
                                sent.resume(answer)
                            }
                        },
                    )
                }
            } catch (e: NotSentAgain) {
                e.status?.let { Sent.Answered(it, Headers.headersOf(), "") } ?: Sent.Unanswered(offline())
            } catch (_: IOException) {
                Sent.Unanswered(offline())
            }
        }

    private fun offline(): Boolean =
        try {
            !device.isOnline()
        } catch (e: Exception) {
            unexpected(UnusableAnswer(e))
            false
        }

    private fun services(host: String): Retrofit {
        offMainThread()
        return Retrofit
            .Builder()
            .baseUrl(host)
            .callFactory(callFactory)
            .addConverterFactory(MalBodies(MalJson.asConverterFactory("application/json".toMediaType())))
            .build()
    }

    private fun offMainThread() = check(!device.isMainThread()) { "The network is used off the main thread" }

    companion object {
        val RepeatReadsAfter = listOf(500.milliseconds, 1500.milliseconds)
        private val ConnectTimeout = 10.seconds
        private val TransferTimeout = 20.seconds
    }
}

// MAL's answer for another user's list that is private or whose name no user has, told by the recorded answers.
internal fun anotherUsersListError(
    status: Int,
    body: String,
): DataError? =
    when {
        isPrivateList(status, body) -> DataError.PrivateList
        status == 404 && errorCode(body) == "not_found" -> DataError.UnknownUser
        else -> null
    }

internal fun isPrivateList(
    status: Int,
    body: String,
): Boolean = status == 403 && errorCode(body) == "not_permitted"

private fun errorCode(body: String): String? =
    try {
        ((Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content
    } catch (_: SerializationException) {
        null
    }

// How a request of [MalHttp.send] ended.
internal sealed interface Sent {
    class Answered(
        val status: Int,
        val headers: Headers,
        val body: String,
    ) : Sent

    class Unanswered(
        val offline: Boolean,
    ) : Sent
}

// A null where a field has a default is that default, as MAL leaving the field out would be.
internal val MalJson =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

// An exception of the network layer without its message, which could quote an answer.
internal class UnusableAnswer(
    cause: Exception,
) : Exception(cause.javaClass.name) {
    init {
        stackTrace = cause.stackTrace
    }
}

// An answer of MAL's the app used in part or not at all, for a value it did not expect; it never quotes a value.
sealed class OddAnswer(
    message: String,
) : Exception(message)

// An answer the mapper read with values left out or filled in, by MAL's field names.
class RepairedAnswer(
    val fields: Set<String>,
) : OddAnswer(fields.joinToString())

// A title's page that answered for another title than the one asked.
class AnotherTitleAnswer : OddAnswer("id")

// A 2xx that is empty, or cannot be read and does not say it is JSON: a portal's or a proxy's page, not MAL's.
internal class NotAnAnswer : IOException()

// Reads a body as JSON and tells an answer that is not MAL's from one of MAL's that cannot be read.
private class MalBodies(
    private val json: Converter.Factory,
) : Converter.Factory() {
    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<ResponseBody, *>? {
        val read = json.responseBodyConverter(type, annotations, retrofit) ?: return null
        return Converter<ResponseBody, Any?> { body ->
            val saysJson = body.contentType()?.subtype.equals("json", ignoreCase = true)
            if (body.source().exhausted()) throw NotAnAnswer()
            try {
                read.convert(body)
            } catch (e: SerializationException) {
                if (saysJson) throw e else throw NotAnAnswer()
            }
        }
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<*, RequestBody>? = json.requestBodyConverter(type, parameterAnnotations, methodAnnotations, retrofit)
}
