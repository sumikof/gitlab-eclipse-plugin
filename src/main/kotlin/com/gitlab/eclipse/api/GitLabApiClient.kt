package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.logger
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Thrown by [GitLabApiClient.captureConnection] and [GitLabApiClient.captureConnectionIf] when
 * the connection config generation keeps changing (or stays in the update-in-progress state)
 * across reads and never settles within the bounded retry budget, so no self-consistent snapshot
 * can be returned.
 */
class UnstableConnectionException : RuntimeException("GitLab connection settings changed during capture")

/** Hex chars kept from the SHA-256 of a token in [authFingerprint]. */
private const val FINGERPRINT_HEX_LENGTH = 16

private fun correlationId(response: java.net.http.HttpResponse<String>): String? =
  response.headers().firstValue("x-request-id").orElse(null)

/**
 * Non-secret, non-reversible, stable identifier of a credential: lowercase-hex SHA-256 of the
 * token truncated to [FINGERPRINT_HEX_LENGTH] chars; blank token → empty string.
 */
private fun authFingerprint(token: String): String {
  if (token.isBlank()) return ""
  return sha256(token).joinToString("") { "%02x".format(it) }.take(FINGERPRINT_HEX_LENGTH)
}

private fun sha256(value: String): ByteArray =
  MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

/**
 * Items of a list fetch plus whether paging stopped early (page cap or a bad `x-next-page`), so more
 * results may exist than [items] holds.
 */
data class PagedResult<T>(val items: List<T>, val truncated: Boolean)

@Suppress("UNCHECKED_CAST")
private fun <T> emptyArrayOf(): Array<T> = arrayOfNulls<Any?>(0) as Array<T>

/**
 * REST endpoint abstraction over [GitLabHttpClient]. Builds URLs from `gitlab.url`,
 * attaches Bearer auth, aggregates paginated list responses, and parses JSON.
 * GraphQL will be added later as a sibling method on this class.
 */
@Suppress("TooManyFunctions")
class GitLabApiClient(
  private val httpClient: GitLabHttpClient = service(),
  private val tokenManager: GitLabTokenProviderManager = service(),
  private val preferenceStore: ScopedPreferenceStore = service(),
  private val bodyDeadline: BodyDeadline = SharedBodyDeadline,
  private val readGeneration: () -> Long = { ConnectionConfigGeneration.generation },
) {
  private val gson = Gson()
  private val logger by lazy { logger<GitLabApiClient>() }

  /**
   * When [connection] is non-null, EVERY page of the fetch is pinned to that same snapshot
   * (URI base + Bearer credential), never re-reading the live preference store / token manager
   * between pages — same contract as [fetchListWithinDeadline]. `connection = null` keeps the
   * pre-existing global-reading behavior for every page, unchanged.
   */
  fun <T> fetchListFromApi(request: ApiRequest<T>, connection: ConnectionSnapshot? = null): List<T> {
    val all = mutableListOf<T>()
    var page = 1
    while (true) {
      val response = sendPage(request, page, connection = connection)
      all.addAll(parseItems(request, response))

      val next = response.headers().firstValue("x-next-page").orElse("").trim()
      if (next.isEmpty()) return all
      val nextPage = next.toIntOrNull()
      if (nextPage == null || nextPage <= page || nextPage > MAX_PAGES) {
        logger.warn("Pagination stopped at page $page (next='$next', cap=$MAX_PAGES); results may be truncated.")
        return all
      }
      page = nextPage
    }
  }

  fun <T> fetchObject(
    path: String,
    query: Map<String, String> = emptyMap(),
    type: Class<T>,
    connection: ConnectionSnapshot? = null,
  ): T {
    return gson.fromJson(sendGet(path, query, connection = connection).body(), type)
  }

  /**
   * Like [fetchListFromApi], but checks [deadline] (elapsed since the call started, measured via
   * [clock]) at the top of every loop iteration — i.e. between pages — and throws
   * [GitLabApiTimeoutException] instead of fetching a further page once it is exceeded. This
   * bounds the total time of a multi-page fetch in a way a coroutine `withTimeout` cannot, since
   * the paging loop itself is synchronous and non-suspending.
   *
   * [isActive] is checked at the same point (between pages) and throws [CancellationException]
   * once it returns false, so a caller running on a cancelled coroutine can abort the paging
   * instead of issuing further requests — again something a suspension-based cancel cannot do
   * here. The check is a plain function (kotlin-stdlib exception, no kotlinx dependency); the
   * default `{ true }` keeps existing callers non-cancellable as before.
   *
   * When [connection] is non-null, EVERY page of the fetch is pinned to that same snapshot
   * (URI base + Bearer credential), never re-reading the live preference store / token manager
   * between pages — so a settings change mid-fetch cannot make a later page hit a different
   * instance or leak the current credential to a newly-configured one. `connection = null` keeps
   * the current global-reading behavior for every page, unchanged.
   */
  fun <T> fetchListWithinDeadline(
    request: ApiRequest<T>,
    deadline: Duration,
    clock: () -> Long = { System.nanoTime() },
    isActive: () -> Boolean = { true },
    connection: ConnectionSnapshot? = null,
  ): List<T> {
    val start = clock()
    val all = mutableListOf<T>()
    var page = 1
    while (true) {
      if (!isActive()) throw CancellationException("Cancelled during paginated fetch")
      val elapsed = clock() - start
      if (elapsed >= deadline.toNanos()) throw GitLabApiTimeoutException(page)

      val remainingNanos = deadline.toNanos() - elapsed
      val timeout = minOf(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS), Duration.ofNanos(remainingNanos))
      val response = sendPage(request, page, timeout, connection)
      all.addAll(parseItems(request, response))

      val next = response.headers().firstValue("x-next-page").orElse("").trim()
      if (next.isEmpty()) return all
      val nextPage = next.toIntOrNull()
      if (nextPage == null || nextPage <= page || nextPage > MAX_PAGES) {
        logger.warn("Pagination stopped at page $page (next='$next', cap=$MAX_PAGES); results may be truncated.")
        return all
      }
      page = nextPage
    }
  }

  /**
   * Fetches a list either up to a result limit or in full, with a truncation flag.
   *
   * [maxItems] non-null (must be 1..100) → exactly one request with `per_page=maxItems&page=1`;
   * the result is never flagged truncated (the limit is the caller's request, not a loss).
   * [maxItems] null → follows `x-next-page` with `per_page=100` like [fetchListFromApi], checking
   * [isActive] before every page (throws [CancellationException] once false, before sending), and
   * flags [PagedResult.truncated] when paging stopped at the 20-page cap or on a non-numeric /
   * non-increasing `x-next-page`.
   *
   * Every page uses one connection: [connection] when given, otherwise a single
   * [captureConnection] taken before the first page — never a per-page re-read of the settings.
   */
  fun <T> fetchListPages(
    request: ApiRequest<T>,
    maxItems: Int?,
    isActive: () -> Boolean,
    connection: ConnectionSnapshot?,
  ): PagedResult<T> {
    if (maxItems != null) require(maxItems in 1..PER_PAGE) { "maxItems must be in 1..$PER_PAGE" }
    if (!isActive()) throw CancellationException("Cancelled before paginated fetch")
    val conn = connection ?: captureConnection()
    if (maxItems != null) {
      val response = sendPage(request, 1, connection = conn, perPage = maxItems)
      return PagedResult(parseItems(request, response), truncated = false)
    }
    val all = mutableListOf<T>()
    var page = 1
    while (true) {
      if (page > 1 && !isActive()) throw CancellationException("Cancelled during paginated fetch")
      val response = sendPage(request, page, connection = conn)
      all.addAll(parseItems(request, response))

      val next = response.headers().firstValue("x-next-page").orElse("").trim()
      if (next.isEmpty()) return PagedResult(all, truncated = false)
      val nextPage = next.toIntOrNull()
      if (nextPage == null || nextPage <= page || nextPage > MAX_PAGES) return PagedResult(all, truncated = true)
      page = nextPage
    }
  }

  private fun <T> parseItems(request: ApiRequest<T>, response: java.net.http.HttpResponse<String>): List<T> {
    val arrayType = TypeToken.getArray(request.elementType).type
    val pageItems: Array<T> = gson.fromJson(response.body(), arrayType) ?: emptyArrayOf()
    return pageItems.asList()
  }

  private fun <T> sendPage(
    request: ApiRequest<T>,
    page: Int,
    timeout: Duration = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS),
    connection: ConnectionSnapshot? = null,
    perPage: Int = PER_PAGE,
  ): java.net.http.HttpResponse<String> {
    val query = LinkedHashMap(request.query).apply {
      put("per_page", perPage.toString())
      put("page", page.toString())
    }
    return sendGet(request.path, query, timeout, connection)
  }

  /**
   * Atomically captures the current (gitlab.url, token) pair via a seqlock read over
   * [ConnectionConfigGeneration]: a `(url, token)` pair is accepted only when the generation was
   * EVEN (no settings update in progress) and UNCHANGED across the two reads bracketing the value
   * reads. Because the settings save brackets its two-store write with beginUpdate/endUpdate, the
   * stable-but-torn `(new url, old token)` intermediate that exists while the URL (preference
   * store) and token (secure storage) are persisted separately is always observed under an odd or
   * changed generation and never returned — a plain value-double-read cannot detect it. No lock is
   * held during the token read, so a slow OAuth refresh cannot block writers or the UI. If the
   * generation never settles within [MAX_CAPTURE_ATTEMPTS], throws [UnstableConnectionException].
   *
   * Both the READ and WRITE paths obtain their pinned connection through this method; the raw
   * (untrimmed) url is kept — trailing-slash trimming happens in [buildUri].
   *
   * Delegates to [captureConnectionIf] with an always-true predicate; the `?:` branch is
   * unreachable (the predicate never rejects) and exists only to satisfy the nullable return
   * type, so this method's signature and meaning are unchanged.
   */
  fun captureConnection(): ConnectionSnapshot =
    captureConnectionIf { true } ?: throw UnstableConnectionException()

  /**
   * Captures the connection only when the configured instance URL is accepted by
   * [acceptInstanceUrl]; otherwise returns null WITHOUT reading the credential (#49). The URL
   * check runs inside the seqlock read — before [tokenManager] is touched — so a gate miss on a
   * mismatched URL cannot trigger an OAuth token refresh (HTTP, persistence, secure-storage
   * writes, LS config notification, user-visible notifications). A rejection is committed only
   * against a settled URL: if the generation moved while the URL was read, the read is discarded
   * and retried, never judged. A rejection observed at a stable generation is final even if the
   * settings change immediately afterwards (no retry on that side).
   *
   * [acceptInstanceUrl] must be pure (no side effects), may be invoked multiple times because of
   * retries, and must not throw; an exception from it is a contract violation and propagates to
   * the caller unhandled. Throws [UnstableConnectionException] if the generation never settles
   * within [MAX_CAPTURE_ATTEMPTS].
   */
  fun captureConnectionIf(acceptInstanceUrl: (String) -> Boolean): ConnectionSnapshot? {
    repeat(MAX_CAPTURE_ATTEMPTS) {
      val g1 = readGeneration()
      if (g1 % 2 != 0L) return@repeat // update in progress -> retry
      val url = preferenceStore.getString(PreferenceConstants.GITLAB_INSTANCE_URL)
      if (!acceptInstanceUrl(url)) {
        // Mismatch — but before committing the rejection, verify via the generation that the URL
        // read was not racing an update. Rejecting unverified could drop, based on a mid-update
        // value, a URL that would have matched once settled.
        if (readGeneration() == g1) return null // settled mismatch -> bail, credentials untouched
        return@repeat // read raced an update -> retry
      }
      // Only reached when the predicate accepted; the token is the one stored for this very URL (#115).
      val token = tokenManager.getToken(url)
      val g2 = readGeneration()
      if (g1 == g2) { // even and unchanged -> consistent pair
        return ConnectionSnapshot(url, token, authFingerprint(token), g1)
      }
    }
    throw UnstableConnectionException()
  }

  /**
   * Sends a body-less POST pinned to [connection]: the URI base and the Bearer credential both
   * come from the snapshot, never from the live preference store / token manager, so a concurrent
   * settings change cannot misroute the write or leak the credential to a different instance.
   * Non-2xx → [GitLabApiException] (with correlation id); timeouts and I/O errors propagate as-is.
   */
  fun post(path: String, query: Map<String, String> = emptyMap(), connection: ConnectionSnapshot): PostResult {
    val response = sendPost(path, query, connection)
    return PostResult(response.statusCode(), correlationId(response))
  }

  /**
   * [post], but also returns the 2xx response body parsed as [type] (e.g. the new job a retry
   * creates). The write has happened once this returns, so a body that does not parse is a null
   * [PostedObject.body], never an exception; the failure is logged by exception class only (the
   * body is server content and stays out of the log). Non-2xx → [GitLabApiException] as for [post].
   */
  fun <T> postReturning(path: String, type: Class<T>, connection: ConnectionSnapshot): PostedObject<T> {
    val response = sendPost(path, emptyMap(), connection)
    val body = try {
      gson.fromJson(response.body(), type)
    } catch (e: JsonParseException) {
      logger.warn("The response of a write could not be parsed: ${e.javaClass.name}")
      null
    }
    return PostedObject(PostResult(response.statusCode(), correlationId(response)), body)
  }

  /**
   * Sends a JSON-body POST pinned to [connection]: the URI base and the Bearer credential both
   * come from the snapshot, never from the live preference store / token manager, mirroring the
   * pinning [sendPost] already does for writes. Returns the raw response body as text (not
   * JSON-parsed) instead of a [PostResult], so the caller receives the server's response payload
   * directly (e.g. the merged YAML from CI lint). Non-2xx → [GitLabApiException] (with
   * correlation id); timeouts and I/O errors propagate as-is.
   */
  fun postJson(path: String, jsonBody: String, connection: ConnectionSnapshot): String {
    val httpRequest = HttpRequest.newBuilder(buildUri(path, emptyMap(), baseOverride = connection.instanceUrl))
      .header("Authorization", "Bearer ${connection.token}")
      .header("Accept", "application/json")
      .header("Content-Type", "application/json")
      .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
      .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
      .build()

    val response = httpClient.send(httpRequest)
    if (response.statusCode() !in SUCCESS_STATUS_MIN..SUCCESS_STATUS_MAX) {
      throw GitLabApiException(response.statusCode(), response.body(), correlationId(response))
    }
    return response.body()
  }

  /**
   * Sends a body-less GET and returns the raw response body as text (not JSON-parsed). When
   * [connection] is non-null, the URI base and the Bearer credential both come from the snapshot
   * instead of the live preference store / token manager, mirroring the pinning [sendPost] already
   * does for writes; when null, [sendGet] falls back to its own [captureConnection] read. Non-2xx
   * → [GitLabApiException] (with correlation id); timeouts and I/O errors propagate as-is.
   */
  fun fetchText(path: String, connection: ConnectionSnapshot? = null): String =
    sendGet(path, emptyMap(), connection = connection).body()

  /**
   * Sends a body-less GET pinned to [connection] (URI base and Bearer credential from the snapshot,
   * never the live stores) and reads the body as bytes under [maxBytes] via [BoundedBytesHandler]:
   * the result is the bytes or [BoundedBytes.TooLarge]. It accepts any media type because raw file
   * content is not JSON. Non-2xx → [GitLabApiException] with the status, the correlation id and an EMPTY
   * body (the error page is discarded unread); timeouts and I/O errors propagate as-is.
   */
  fun fetchBytes(
    path: String,
    query: Map<String, String>,
    maxBytes: Int,
    connection: ConnectionSnapshot,
  ): BoundedBytes {
    val httpRequest = HttpRequest.newBuilder(buildUri(path, query, baseOverride = connection.instanceUrl))
      .header("Authorization", "Bearer ${connection.token}")
      .header("Accept", "*/*")
      .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
      .GET()
      .build()

    val response = httpClient.send(httpRequest, BoundedBytesHandler(maxBytes))
    val body = response.body()
    if (response.statusCode() !in SUCCESS_STATUS_MIN..SUCCESS_STATUS_MAX || body == null) {
      throw GitLabApiException(
        response.statusCode(),
        "",
        response.headers().firstValue("x-request-id").orElse(null),
      )
    }
    return body
  }

  /**
   * Conditional GET of the last [maxBytes] of a trace, pinned to [connection]. [ifNoneMatch] is sent as given.
   * 304 → [TraceFetch.NotModified]; other non-2xx → [GitLabApiException] (status and correlation id only, the
   * body is never read); timeouts and I/O errors propagate as-is.
   */
  fun fetchTrace(path: String, ifNoneMatch: String?, maxBytes: Int, connection: ConnectionSnapshot): TraceFetch {
    val builder = HttpRequest.newBuilder(buildUri(path, emptyMap(), baseOverride = connection.instanceUrl))
      .header("Authorization", "Bearer ${connection.token}")
      .header("Accept", "*/*")
      .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
    if (ifNoneMatch != null) builder.header("If-None-Match", ifNoneMatch)

    val response = httpClient.send(builder.GET().build(), TailKeepingBodyHandler(maxBytes, deadline = bodyDeadline))
    return when (val outcome = response.body()) {
      is TailOutcome.Done -> outcome.fetch
      is TailOutcome.Rejected -> throw GitLabApiException(outcome.status, "", outcome.correlationId)
    }
  }

  /**
   * Streams a response body to [sink] without any size cap, pinned to [connection]. Non-2xx →
   * [GitLabApiException] without reading the body. The body must finish within [DOWNLOAD_BODY_TIMEOUT]: on expiry
   * the stream is closed and the call fails with [HttpTimeoutException]. [onOpened] receives a function that closes
   * the body at once, for a caller that is cancelled while [sink] is blocked in a read (the read then fails).
   */
  fun downloadTo(
    path: String,
    connection: ConnectionSnapshot,
    onOpened: (closeNow: () -> Unit) -> Unit = {},
    sink: (InputStream) -> Unit,
  ) {
    val httpRequest = HttpRequest.newBuilder(buildUri(path, emptyMap(), baseOverride = connection.instanceUrl))
      .header("Authorization", "Bearer ${connection.token}")
      .header("Accept", "*/*")
      .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
      .GET()
      .build()

    val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
    val stream = response.body()
    val expired = AtomicBoolean(false)
    val disarm = bodyDeadline.arm(DOWNLOAD_BODY_TIMEOUT) {
      expired.set(true)
      closeQuietly(stream)
    }
    try {
      if (response.statusCode() !in SUCCESS_STATUS_MIN..SUCCESS_STATUS_MAX) {
        throw GitLabApiException(response.statusCode(), "", response.headers().firstValue("x-request-id").orElse(null))
      }
      onOpened { closeQuietly(stream) }
      sink(stream)
    } catch (e: IOException) {
      throw if (expired.get()) HttpTimeoutException("Response body not received in time") else e
    } finally {
      disarm()
      closeQuietly(stream)
    }
  }

  private fun closeQuietly(stream: InputStream) {
    try {
      stream.close()
    } catch (ignored: IOException) {
      // Closing only releases the connection; there is nothing to recover.
    }
  }

  private fun sendPost(
    path: String,
    query: Map<String, String>,
    connection: ConnectionSnapshot,
    timeout: Duration = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS),
  ): java.net.http.HttpResponse<String> {
    val httpRequest = HttpRequest.newBuilder(buildUri(path, query, baseOverride = connection.instanceUrl))
      .header("Authorization", "Bearer ${connection.token}")
      .header("Accept", "application/json")
      .timeout(timeout)
      .POST(HttpRequest.BodyPublishers.noBody())
      .build()

    val response = httpClient.send(httpRequest)
    if (response.statusCode() !in SUCCESS_STATUS_MIN..SUCCESS_STATUS_MAX) {
      throw GitLabApiException(response.statusCode(), response.body(), correlationId(response))
    }
    return response
  }

  /**
   * Sends a GET request. When [connection] is non-null, the URI base and the Bearer credential
   * both come from the snapshot instead of the live preference store / token manager, mirroring
   * the pinning [sendPost] already does for writes. When [connection] is null, a
   * generation-consistent snapshot is obtained via [captureConnection] for this single request,
   * so even an unpinned GET can never send a torn `(new url, old token)` pair while a settings
   * save is writing the two stores; at a stable generation the request is identical to a plain
   * global read. May throw [UnstableConnectionException] if the settings never settle.
   */
  private fun sendGet(
    path: String,
    query: Map<String, String>,
    timeout: Duration = Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS),
    connection: ConnectionSnapshot? = null,
  ): java.net.http.HttpResponse<String> {
    val conn = connection ?: captureConnection()
    val httpRequest = HttpRequest.newBuilder(buildUri(path, query, baseOverride = conn.instanceUrl))
      .header("Authorization", "Bearer ${conn.token}")
      .header("Accept", "application/json")
      .timeout(timeout)
      .GET()
      .build()

    val response = httpClient.send(httpRequest)
    if (response.statusCode() !in SUCCESS_STATUS_MIN..SUCCESS_STATUS_MAX) {
      throw GitLabApiException(response.statusCode(), response.body(), correlationId(response))
    }
    return response
  }

  private fun buildUri(path: String, query: Map<String, String>, baseOverride: String? = null): URI {
    val base = (baseOverride ?: preferenceStore.getString(PreferenceConstants.GITLAB_INSTANCE_URL)).trimEnd('/')
    val queryString = query.entries.joinToString("&") { (k, v) ->
      "${encode(k)}=${encode(v)}"
    }
    return URI.create("$base/api/v4$path?$queryString")
  }

  companion object {
    private const val PER_PAGE = 100
    private const val MAX_PAGES = 20
    private const val REQUEST_TIMEOUT_SECONDS = 30L
    private const val DOWNLOAD_BODY_TIMEOUT_MINUTES = 10L
    private val DOWNLOAD_BODY_TIMEOUT: Duration = Duration.ofMinutes(DOWNLOAD_BODY_TIMEOUT_MINUTES)

    /** Inclusive bounds of the HTTP 2xx (successful) status class. */
    private const val SUCCESS_STATUS_MIN = 200
    private const val SUCCESS_STATUS_MAX = 299

    /** Bounded retry budget of the seqlock read loop in [captureConnectionIf]. */
    private const val MAX_CAPTURE_ATTEMPTS = 8
  }
}
