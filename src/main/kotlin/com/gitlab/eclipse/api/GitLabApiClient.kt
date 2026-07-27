package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.logger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * REST endpoint abstraction over [GitLabHttpClient]. Builds URLs from `gitlab.url`,
 * attaches Bearer auth, aggregates paginated list responses, and parses JSON.
 * GraphQL will be added later as a sibling method on this class.
 */
class GitLabApiClient(
  private val httpClient: GitLabHttpClient = service(),
  private val tokenManager: GitLabTokenProviderManager = service(),
  private val preferenceStore: ScopedPreferenceStore = service(),
) {
  private val gson = Gson()
  private val logger by lazy { logger<GitLabApiClient>() }

  fun <T> fetchListFromApi(request: ApiRequest<T>): List<T> {
    val all = mutableListOf<T>()
    var page = 1
    while (true) {
      val response = sendPage(request, page)
      val arrayType = TypeToken.getArray(request.elementType).type
      val pageItems: Array<T> = gson.fromJson(response.body(), arrayType) ?: emptyArrayOf()
      all.addAll(pageItems)

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

  fun <T> fetchObject(path: String, query: Map<String, String> = emptyMap(), type: Class<T>): T {
    return gson.fromJson(sendGet(path, query).body(), type)
  }

  private fun <T> sendPage(request: ApiRequest<T>, page: Int): java.net.http.HttpResponse<String> {
    val query = LinkedHashMap(request.query).apply {
      put("per_page", PER_PAGE.toString())
      put("page", page.toString())
    }
    return sendGet(request.path, query)
  }

  private fun sendGet(path: String, query: Map<String, String>): java.net.http.HttpResponse<String> {
    val httpRequest = HttpRequest.newBuilder(buildUri(path, query))
      .header("Authorization", "Bearer ${tokenManager.getToken()}")
      .header("Accept", "application/json")
      .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
      .GET()
      .build()

    val response = httpClient.send(httpRequest)
    if (response.statusCode() !in SUCCESS_STATUS_MIN..SUCCESS_STATUS_MAX) {
      throw GitLabApiException(response.statusCode(), response.body())
    }
    return response
  }

  private fun buildUri(path: String, query: Map<String, String>): URI {
    val base = preferenceStore.getString(PreferenceConstants.GITLAB_INSTANCE_URL).trimEnd('/')
    val queryString = query.entries.joinToString("&") { (k, v) ->
      "${encode(k)}=${encode(v)}"
    }
    return URI.create("$base/api/v4$path?$queryString")
  }

  private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

  @Suppress("UNCHECKED_CAST")
  private fun <T> emptyArrayOf(): Array<T> = arrayOfNulls<Any?>(0) as Array<T>

  companion object {
    private const val PER_PAGE = 100
    private const val MAX_PAGES = 20
    private const val REQUEST_TIMEOUT_SECONDS = 30L

    /** Inclusive bounds of the HTTP 2xx (successful) status class. */
    private const val SUCCESS_STATUS_MIN = 200
    private const val SUCCESS_STATUS_MAX = 299
  }
}
