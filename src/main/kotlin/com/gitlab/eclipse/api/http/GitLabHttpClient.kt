package com.gitlab.eclipse.api.http

import com.gitlab.eclipse.inject.service
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Holds the current [HttpClient] and rebuilds it (atomically) whenever the egress
 * config snapshot changes, so a settings-page edit is picked up on the next request
 * without an IDE restart.
 */
class GitLabHttpClient(private val factory: GitLabHttpClientFactory = service()) {
  private val lock = Any()
  private var cachedClient: HttpClient? = null
  private var cachedSnapshot: EgressConfigSnapshot? = null

  fun send(request: HttpRequest): HttpResponse<String> =
    rebuildIfNeeded().send(request, HttpResponse.BodyHandlers.ofString())

  /** Visible for testing; returns the client rebuilt to match the current snapshot. */
  fun rebuildIfNeeded(): HttpClient = synchronized(lock) {
    val snapshot = factory.currentSnapshot()
    if (cachedClient == null || cachedSnapshot != snapshot) {
      cachedClient = factory.create(snapshot)
      cachedSnapshot = snapshot
    }
    cachedClient!!
  }
}
