package io.github.kauri_off.kiroku.data.session

import io.github.kauri_off.kiroku.core.error.DataError
import io.github.kauri_off.kiroku.data.network.MalHttp
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.time.Clock

// The session of one data source: its tokens in [file], which nothing else reads or writes, refreshed over [http].
fun malSession(
    file: File,
    http: MalHttp,
    clock: Clock,
    scope: CoroutineScope,
    unexpected: (Throwable) -> Unit,
): MalSession = malSession(file, tokenEndpoint(http), clock, scope, unexpected)

// No token request is sent yet: a refresh fails for now and keeps the session.
@Suppress("UNUSED_PARAMETER")
internal fun tokenEndpoint(http: MalHttp): TokenEndpoint = TokenEndpoint { TokenReply.NoAnswer(DataError.ServerError) }
