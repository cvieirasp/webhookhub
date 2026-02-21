package io.github.cvieirasp.worker.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Thin Ktor-based HTTP client for delivering webhook payloads.
 *
 * Three independent timeout axes are configured explicitly:
 *
 * | Timeout | Value | Guards against |
 * |---|---|---|
 * | Connect | 5 s  | Unreachable / overloaded hosts at TCP handshake time |
 * | Socket  | 15 s | Stalled responses — data stops arriving mid-transfer |
 * | Request | 30 s | Total wall-clock budget for the full round-trip |
 *
 * A non-2xx response is treated as a soft failure (returned as [Result.Failure])
 * so the caller can decide whether to retry.  Network-level exceptions (timeout,
 * DNS failure, TLS error, etc.) are caught and also returned as [Result.Failure].
 *
 * Every [Result.Failure] carries a [Result.Failure.retryable] flag:
 *
 * | Condition | retryable |
 * |---|---|
 * | Network / timeout exception | true  |
 * | HTTP 429 Too Many Requests  | true  |
 * | HTTP 5xx server error       | true  |
 * | HTTP 4xx client error (≠429)| false |
 */
class HttpDeliveryClient {

    private companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000L   //  5 s — TCP handshake
        private const val SOCKET_TIMEOUT_MS  = 15_000L  // 15 s — idle socket (no data)
        private const val REQUEST_TIMEOUT_MS = 30_000L  // 30 s — full round-trip budget

        private val logger = LoggerFactory.getLogger(HttpDeliveryClient::class.java)
    }

    private val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis  = SOCKET_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    /**
     * POSTs [payload] (raw JSON string) to [url] with `Content-Type: application/json`.
     *
     * Returns [Result.Success] for any 2xx response, or [Result.Failure] with a
     * human-readable message for non-2xx responses and network/timeout exceptions.
     */
    fun post(url: String, payload: String): Result {
        logger.debug("POST {} ({} bytes)", url, payload.length)
        return try {
            val response = runBlocking {
                client.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            }
            if (response.status.isSuccess()) {
                logger.debug("POST {} → {}", url, response.status.value)
                Result.Success
            } else {
                val code      = response.status.value
                val retryable = code == 429 || code in 500..599
                logger.warn("POST {} → HTTP {} ({})", url, code, if (retryable) "retryable" else "non-retryable")
                Result.Failure(
                    message    = "HTTP $code from $url",
                    statusCode = code,
                    retryable  = retryable,
                )
            }
        } catch (e: Exception) {
            logger.warn("POST {} failed (retryable): {}", url, e.message)
            Result.Failure(
                message    = e.message ?: "Unknown error",
                statusCode = null,
                retryable  = true,
            )
        }
    }

    /** Releases the underlying Ktor engine and its connection pool. */
    fun close() = client.close()

    sealed interface Result {
        data object Success : Result

        /**
         * @param message   Human-readable description of the error.
         * @param statusCode HTTP status code, or `null` for network-level failures
         *                   (timeout, DNS error, TLS error, etc.).
         * @param retryable  `true` when the caller should schedule a retry:
         *                   network failures, HTTP 429, and HTTP 5xx.
         *                   `false` for HTTP 4xx responses other than 429,
         *                   which indicate a permanent client-side problem.
         */
        data class Failure(
            val message: String,
            val statusCode: Int?,
            val retryable: Boolean,
        ) : Result
    }
}
