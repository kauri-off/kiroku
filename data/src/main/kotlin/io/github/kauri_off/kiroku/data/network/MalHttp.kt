package io.github.kauri_off.kiroku.data.network

import io.github.kauri_off.kiroku.core.error.DataError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.time.Clock
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import java.time.Duration as JavaDuration

// The one HTTP client for MAL and what sends a request through it (section 6.4). Nothing is built before its first
// use, and a use on the main thread fails. [unexpected] hears of an answer that could not be used, without its text.
class MalHttp(
    internal val hosts: MalHosts,
    internal val clientId: String,
    appVersion: String,
    private val device: Device,
    private val clock: Clock,
    private val unexpected: (Throwable) -> Unit,
    log: ((String) -> Unit)? = null,
    repeatReadsAfter: List<Duration> = RepeatReadsAfter,
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
            .addInterceptor(SendRules(repeatReadsAfter) { !offline() })
            .apply { if (log != null) addInterceptor(RequestLog(log)) }
            .addNetworkInterceptor(ClientId(hosts.api.toHttpUrl(), clientId))
            .addNetworkInterceptor(WriteOnce)
            .build()
    }

    // For the image loader, which asks off the main thread.
    val callFactory: Call.Factory = Call.Factory { client.newCall(it) }

    private val apiServices by lazy { services(hosts.api) }
    private val signInServices by lazy { services(hosts.signIn) }

    // A Retrofit service of MAL's API, made on its first use; its methods return the answer's body and are called
    // inside [call].
    internal fun <S : Any> api(service: KClass<S>): Lazy<S> = lazy { apiServices.create(service.java) }

    internal fun <S : Any> signIn(service: KClass<S>): Lazy<S> = lazy { signInServices.create(service.java) }

    // Runs [request] off the main thread and types its failure by the status alone. A refusal of
    // [anotherUsersList] is a private list and no reason to back off.
    internal suspend fun <T> call(
        anotherUsersList: Boolean = false,
        request: suspend () -> T,
    ): MalAnswer<T> =
        withContext(io) {
            try {
                MalAnswer.Success(request()).also { backOff.answered() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                failure(e, anotherUsersList)
            } catch (_: IOException) {
                MalAnswer.Failed(if (offline()) DataError.NoConnection else DataError.ServerError)
            } catch (e: Exception) {
                unexpected(UnusableAnswer(e))
                MalAnswer.Failed(DataError.ServerError)
            }
        }

    private fun failure(
        e: HttpException,
        anotherUsersList: Boolean,
    ): MalAnswer<Nothing> {
        val status = e.code()
        val error =
            when {
                status == 401 -> return MalAnswer.Rejected
                status == 403 && anotherUsersList -> DataError.PrivateList
                status == 403 || status == 429 -> DataError.Refused
                status == 404 -> if (anotherUsersList) DataError.UnknownUser else DataError.NotFound
                status == 408 || status >= 500 -> DataError.ServerError
                status >= 400 -> DataError.InvalidRequest
                else -> DataError.ServerError
            }
        if (error == DataError.Refused) backOff.refused(retryAfter(e))
        return MalAnswer.Failed(error)
    }

    private fun retryAfter(e: HttpException): JavaDuration? {
        val headers = e.response()?.headers() ?: return null
        val seconds = headers[RETRY_AFTER]?.trim()?.toLongOrNull()
        if (seconds != null) return JavaDuration.ofSeconds(seconds.coerceAtLeast(0))
        return headers.getInstant(RETRY_AFTER)?.let { JavaDuration.between(clock.instant(), it) }
    }

    private fun offline(): Boolean =
        try {
            !device.isOnline()
        } catch (e: Exception) {
            unexpected(e)
            false
        }

    private fun services(host: String): Retrofit {
        offMainThread()
        return Retrofit
            .Builder()
            .baseUrl(host)
            .callFactory(callFactory)
            .addConverterFactory(MalJson.asConverterFactory("application/json".toMediaType()))
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

// An exception of a request without its message, which could quote the answer.
internal class UnusableAnswer(
    cause: Exception,
) : Exception(cause.javaClass.name) {
    init {
        stackTrace = cause.stackTrace
    }
}
