package com.neutrino.drive.core.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Client identity. Each value is registered or relied on server-side — see the root CLAUDE.md. */
object AppConfig {
    /** Seeded by `neutrino/migrations/00141_oauth__*`. A client id the server does not know is an opaque sign-in failure. */
    const val OAUTH_CLIENT_ID = "neutrino-android"
    /** Shared by every native client; the flow never leaves the process, so it disambiguates nothing. */
    const val OAUTH_REDIRECT_URI = "neutrino://oauth/callback"
    const val DEFAULT_SERVER_URL = "https://www.getneutrino.app"
    const val DISPLAY_NAME = "Neutrino Drive"
    const val DEVICE_NAME_HEADER = "X-Device-Name"

    /**
     * The device name as a header value the server can read.
     *
     * The server reads `X-Device-Name` with actix's `to_str()`, which rejects any non-ASCII byte
     * and then records no name at all — so "Pixel — Neutrino Drive" would list as "Unknown device"
     * (OkHttp refuses to send it anyway). Dashes and quotes are folded to ASCII, accents stripped,
     * and anything left that is not printable ASCII dropped.
     */
    fun headerSafeDeviceName(name: String): String {
        val folded = name.replace('\u2014', '-').replace('\u2013', '-')
            .replace('\u2018', '\'').replace('\u2019', '\'').replace('\u201C', '"').replace('\u201D', '"')
        val stripped = java.text.Normalizer.normalize(folded, java.text.Normalizer.Form.NFKD)
            .filter { it in ' '..'~' }
            .replace(Regex("\\s+"), " ").trim()
        return stripped.ifEmpty { "Android - $DISPLAY_NAME" }
    }

    /** `https://example.com/` → `https://example.com`; a bare host gets `https://`. */
    fun normalizeServerUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isEmpty()) return DEFAULT_SERVER_URL
        return if (trimmed.contains("://")) trimmed else "https://$trimmed"
    }
}

/** Every failure a request can end in, with text a person can act on. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotSignedIn : ApiException("You are not signed in.")
    class SessionExpired : ApiException("Your session has expired. Please sign in again.")
    class Network(cause: IOException) : ApiException("A network error occurred. Please check your connection.", cause)
    class Http(val status: Int, val code: String?, val serverMessage: String?, body: String) :
        ApiException(serverMessage?.takeIf { it.isNotBlank() } ?: "Server error ($status).") {
        val body: String = body.take(500)
    }
    class Decode(detail: String) : ApiException("Failed to read the server's response: $detail")

    /** Worth sending again later: a transport error, 408, 429 or a 5xx. */
    val isTransient: Boolean
        get() = this is Network || (this is Http && (status >= 500 || status == 429 || status == 408))

    val httpStatus: Int? get() = (this as? Http)?.status
}

internal val apiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** Suspends on an OkHttp call, cancelling it with the coroutine. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(ApiException.Network(e))
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** Neutrino wraps errors as `{"error": {"code", "message"}}`; some older handlers answer `{"message"}`. */
internal fun httpError(status: Int, body: String): ApiException.Http {
    val parsed = runCatching { apiJson.parseToJsonElement(body).jsonObject }.getOrNull()
    val error = runCatching { parsed?.get("error")?.jsonObject }.getOrNull()
    val code = runCatching { error?.get("code")?.jsonPrimitive?.content }.getOrNull()
    val message = runCatching {
        (error?.get("message") ?: parsed?.get("message"))?.jsonPrimitive?.content
    }.getOrNull()
    return ApiException.Http(status, code, message, body)
}

/**
 * Dates as the server sends them.
 *
 * File and folder DTOs serialise a zone-less `NaiveDateTime` (`2026-01-02T03:04:05.123456`), which
 * is UTC; the version DTO sends RFC 3339 with an offset. Both are accepted.
 */
object DriveDate {
    fun parse(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(raw) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    /** RFC 3339 in UTC — the shape the server's `parse_import_timestamp` pins to an instant. */
    fun wire(instant: Instant): String = instant.atOffset(ZoneOffset.UTC).withNano(0).toString()
        .let { if (it.endsWith("Z")) it else it.replace("+00:00", "Z") }
}
