package io.github.kauri_off.kiroku.data.network

import io.github.kauri_off.kiroku.core.error.DataError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.reflect.Type
import java.time.Clock
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
    // Hears of an answer that could not be used and of a fault of this layer, each without its message.
    private val unexpected: (Throwable) -> Unit,
    log: ((String) -> Unit)? = null,
    private val repeatReadsAfter: List<Duration> = RepeatReadsAfter,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val backOff = MalBackOff(clock)

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
        // A refusal is then a private list, and no reason to back off.
        anotherUsersList: Boolean = false,
        request: suspend () -> T,
    ): MalAnswer<T> {
        val sends = Sends(limit = 1 + repeatReadsAfter.size)
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
            answered(e.code(), e.response()?.headers(), anotherUsersList)
        } catch (e: NotSentAgain) {
            e.status?.let { answered(it, null, anotherUsersList) } ?: unanswered(e.failure)
        } catch (_: NotAnAnswer) {
            Tried(MalAnswer.Failed(DataError.ServerError))
        } catch (e: IOException) {
            unanswered(e)
        } catch (e: Exception) {
            unusable(e)
        }
    }

    private fun answered(
        status: Int,
        headers: Headers?,
        anotherUsersList: Boolean,
    ): Tried<Nothing> {
        val error =
            when {
                status == 401 -> return Tried(MalAnswer.Rejected)
                status == 403 && anotherUsersList -> DataError.PrivateList
                status == 403 || status == 429 -> DataError.Refused
                status == 404 -> if (anotherUsersList) DataError.UnknownUser else DataError.NotFound
                status == 408 || status >= 500 -> DataError.ServerError
                status >= 400 -> DataError.InvalidRequest
                else -> DataError.ServerError
            }
        if (error == DataError.Refused) backOff.refused(headers?.let(::retryAfter))
        return Tried(MalAnswer.Failed(error), again = status >= 500)
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

    private fun retryAfter(headers: Headers): JavaDuration? {
        val seconds = headers[RETRY_AFTER]?.trim()?.toLongOrNull()
        if (seconds != null) return JavaDuration.ofSeconds(seconds)
        return headers.getInstant(RETRY_AFTER)?.let { JavaDuration.between(clock.instant(), it) }
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
        private const val RETRY_AFTER = "Retry-After"
    }
}

internal val MalJson = Json { ignoreUnknownKeys = true }

// An exception of the network layer without its message, which could quote an answer.
internal class UnusableAnswer(
    cause: Exception,
) : Exception(cause.javaClass.name) {
    init {
        stackTrace = cause.stackTrace
    }
}

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
