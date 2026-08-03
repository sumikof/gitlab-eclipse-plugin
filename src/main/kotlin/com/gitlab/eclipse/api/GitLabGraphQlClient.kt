package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.inject.service
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import java.net.URI
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Body shape for a GraphQL POST: field order matters, hence a data class rather than a map. */
private data class GraphQlRequestBody(val query: String, val variables: Map<String, Any?>)

private fun correlationId(response: java.net.http.HttpResponse<String>): String? =
  response.headers().firstValue("x-request-id").orElse(null)

/**
 * GraphQL transport over [GitLabHttpClient]. Knows nothing about discussions, merge requests, or
 * any particular query — [DiscussionService] (a later task) owns query strings, paging, and
 * domain types.
 *
 * The endpoint is `<instanceUrl>/api/graphql` (NOT `/api/v4/graphql`), confirmed against the
 * reference implementation:
 * `out/gitlab-vscode-extension/src/common/gitlab/api/api_client.ts:151-152`
 * (`new URL('./api/graphql', ensureEndsWithSlash(this.#instanceUrl)).href`).
 * [GitLabApiClient.buildUri] (`GitLabApiClient.kt:281-287`) hard-codes `/api/v4` into every URI,
 * so it cannot be reused here; the URI is built directly in this class.
 *
 * The URI base and the Bearer credential come only from [ConnectionSnapshot] — this class never
 * reads the preference store or the token manager, directly or indirectly, on the [execute] path,
 * so a settings change mid-flight cannot misroute the request or send one instance's credential
 * to another. This mirrors [GitLabApiClient.postJson] (`GitLabApiClient.kt:205-219`).
 *
 * This class logs nothing: not the query, the variables, the request body, the response body,
 * the token, or an exception object.
 */
class GitLabGraphQlClient(
  private val httpClient: GitLabHttpClient = service(),
  private val apiClient: GitLabApiClient = service(),
) {
  private val gson = Gson()

  /**
   * Sends [query]/[variables] to `<connection.instanceUrl>/api/graphql` with the Bearer
   * credential from [connection], bounded by [timeout] (no default — callers must always choose;
   * a paginated fetch passes `min(30s, time left in the overall deadline)`).
   *
   * A non-2xx status throws [GitLabApiException] before the body is parsed. A 2xx body with a
   * non-empty top-level `errors` array throws [GraphQlException] — even on 2xx, since GraphQL
   * reports query-syntax errors, schema mismatches, authorization failures, and resolver failures
   * inside a 200 response. A body with no usable `data` (and no `errors`), or one whose `errors`
   * member is present but not a JSON array, is uninterpretable and throws [JsonSyntaxException];
   * a later PR treats that as *Ambiguous* (result unknown), which a forced [GraphQlException] —
   * or, worse, a success — would misrepresent.
   *
   * [T] is the shape of the `data` payload, not the whole response envelope — callers declare a
   * DTO matching what their query selects under `data`.
   *
   * Timeouts and [java.io.IOException] from `httpClient.send` propagate as-is.
   */
  fun <T> execute(
    query: String,
    variables: Map<String, Any?>,
    type: Class<T>,
    connection: ConnectionSnapshot,
    timeout: Duration,
  ): T {
    val requestBody = gson.toJson(GraphQlRequestBody(query, variables))
    val httpRequest = HttpRequest.newBuilder(graphQlUri(connection.instanceUrl))
      .header("Authorization", "Bearer ${connection.token}")
      .header("Accept", "application/json")
      .header("Content-Type", "application/json")
      .timeout(timeout)
      .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
      .build()

    val response = httpClient.send(httpRequest)
    if (response.statusCode() !in SUCCESS_STATUS_MIN..SUCCESS_STATUS_MAX) {
      throw GitLabApiException(response.statusCode(), response.body(), correlationId(response))
    }

    return parseGraphQlResponse(response.body(), correlationId(response), type)
  }

  private fun <T> parseGraphQlResponse(body: String, correlationId: String?, type: Class<T>): T {
    val root = parseRootObject(body)
    val hasDataKey = root.has("data")

    throwIfErrorsMember(root, hasDataKey, correlationId)

    val dataElement = root.get("data")?.takeUnless { it.isJsonNull } ?: throw noDataException()

    return gson.fromJson(dataElement, type)
  }

  /**
   * Keyed on the *presence* of `errors`, not on it being an array: a 2xx envelope carrying a
   * non-array errors member (object, string, number, JSON null) is not a shape the GraphQL spec
   * permits, and reading it as success would convert a failure into a success. It lands in the
   * same [JsonSyntaxException] bucket as every other uninterpretable response (a later PR
   * classifies that bucket as "result unknown" — the safe reading). A present but EMPTY array is
   * still not a failure. Returns normally when there is no failure to report.
   */
  private fun throwIfErrorsMember(root: JsonObject, hasDataKey: Boolean, correlationId: String?) {
    if (!root.has("errors")) return
    val errorsElement = root.get("errors")
    if (!errorsElement.isJsonArray) {
      throw JsonSyntaxException("GraphQL response carried a non-array errors member")
    }
    val errors = errorsElement.asJsonArray
    if (!errors.isEmpty) {
      throw GraphQlException(hasDataKey, extractErrorMessages(errors), correlationId)
    }
  }

  /**
   * Parses [body] into a [JsonObject], demanding a JSON object so that every uninterpretable
   * response (empty body, non-object JSON, or invalid JSON) lands in the single
   * [JsonSyntaxException] bucket a later PR relies on for its Ambiguous classification.
   */
  private fun parseRootObject(body: String): JsonObject =
    gson.fromJson(body, JsonElement::class.java)?.takeIf { it.isJsonObject }?.asJsonObject ?: throw noDataException()

  private fun noDataException() = JsonSyntaxException("GraphQL response contained no data")

  private fun extractErrorMessages(errors: JsonArray): List<String> =
    errors.mapNotNull { element ->
      if (!element.isJsonObject) return@mapNotNull null
      element.asJsonObject.get("message")?.takeIf { it.isJsonPrimitive }?.asString
    }

  /**
   * Delegates to [GitLabApiClient.captureConnection]; the seqlock logic lives in one place only.
   * No production caller today — the discussions read path pins its connection through a different
   * helper — but it exists for the write path in the follow-up PR, so do not delete it as dead code.
   */
  fun captureConnection(): ConnectionSnapshot = apiClient.captureConnection()

  companion object {
    /** Inclusive bounds of the HTTP 2xx (successful) status class. */
    private const val SUCCESS_STATUS_MIN = 200
    private const val SUCCESS_STATUS_MAX = 299

    private fun graphQlUri(instanceUrl: String): URI = URI.create("${instanceUrl.trimEnd('/')}/api/graphql")
  }
}
