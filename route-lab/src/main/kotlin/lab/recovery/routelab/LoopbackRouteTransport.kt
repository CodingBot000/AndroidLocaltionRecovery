package lab.recovery.routelab

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit.MILLISECONDS
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

internal data class HttpTimeouts(
    val connectMs: Long = 2_000,
    val readMs: Long = 3_000,
    val writeMs: Long = 3_000,
    val callMs: Long = 5_000,
)

/** The lab has one numeric IPv4 loopback endpoint, with no proxy, DNS, authentication or redirects. */
internal class LoopbackRouteTransport(
    port: Int,
    timeouts: HttpTimeouts = HttpTimeouts(),
    private val wallClock: Clock = Clock.systemUTC(),
) : RouteTransport, Closeable {
    private val url = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(port)
        .addPathSegment("route").addPathSegment("lookup").build()
    private val dispatcher = Dispatcher().apply {
        maxRequests = 1
        maxRequestsPerHost = 1
    }
    private val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(timeouts.connectMs, MILLISECONDS)
        .readTimeout(timeouts.readMs, MILLISECONDS)
        .writeTimeout(timeouts.writeMs, MILLISECONDS)
        .callTimeout(timeouts.callMs, MILLISECONDS)
        .build()

    val runningCalls get() = dispatcher.runningCallsCount()
    val queuedCalls get() = dispatcher.queuedCallsCount()

    override suspend fun request(query: RouteRequest): TransportResult = suspendCancellableCoroutine { result ->
        // The fixture endpoint is a read-only, idempotent lookup. Backoff belongs to the coordinator.
        val encoded = query.encode().toRequestBody("application/json".toMediaType())
        // A logical retry gets a new body. One-shot also suppresses OkHttp's 503/Retry-After: 0 follow-up.
        val body = object : RequestBody() {
            override fun contentType() = encoded.contentType()
            override fun contentLength() = encoded.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = encoded.writeTo(sink)
        }
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .post(body)
            .build()
        val call = client.newCall(request)
        result.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = result.resume(ioFailure(e))

            override fun onResponse(call: Call, response: Response) {
                val decoded = try {
                    response.use(::decode)
                } catch (error: IOException) {
                    ioFailure(error)
                }
                result.resume(decoded)
            }
        })
    }

    private fun decode(response: Response): TransportResult {
        if (!response.isSuccessful) {
            val retry = response.code in retryableStatuses
            return TransportResult.Failure(RouteError.HTTP_STATUS, response.code,
                response.header("Retry-After")?.let(::retryAfterMs), retry)
        }
        val body = response.body ?: return schemaFailure
        if (body.contentLength() > maxResponseBytes) return schemaFailure
        val bytes = Buffer()
        val source = body.source()
        while (bytes.size <= maxResponseBytes) {
            val limit = maxResponseBytes + 1 - bytes.size
            if (source.read(bytes, limit) < 0) break
        }
        if (bytes.size > maxResponseBytes) return schemaFailure
        return try {
            TransportResult.Response(RouteResponse.decode(bytes.readUtf8()))
        } catch (_: IOException) {
            schemaFailure
        } catch (_: IllegalArgumentException) {
            schemaFailure
        } catch (_: IllegalStateException) {
            schemaFailure
        }
    }

    private fun retryAfterMs(value: String): Long? {
        val trimmed = value.trim()
        trimmed.toLongOrNull()?.let { seconds ->
            if (seconds < 0) return null
            return seconds.coerceAtMost(Long.MAX_VALUE / 1_000) * 1_000
        }
        return runCatching {
            val time = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            (time.toEpochMilli() - wallClock.millis()).coerceAtLeast(0)
        }.getOrNull()
    }

    private fun ioFailure(failure: IOException): TransportResult.Failure = when (failure) {
        is SSLException -> TransportResult.Failure(RouteError.IO)
        is InterruptedIOException -> TransportResult.Failure(RouteError.TIMEOUT, retryable = true)
        else -> TransportResult.Failure(RouteError.IO, retryable = true)
    }

    override fun close() {
        dispatcher.cancelAll()
        client.connectionPool.evictAll()
        dispatcher.executorService.shutdown()
    }

    private companion object {
        val retryableStatuses = setOf(408, 429, 502, 503, 504)
        val schemaFailure = TransportResult.Failure(RouteError.SCHEMA)
    }
}
