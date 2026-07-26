package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.api.model.GitLabUser
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.net.http.HttpResponse

class GitLabApiClientFetchObjectTest : StringSpec({
  "fetchObject parses a single object" {
    val http = mockk<GitLabHttpClient>()
    val tokens = mockk<GitLabTokenProviderManager>()
    every { tokens.getToken() } returns "t"
    val prefs = mockk<ScopedPreferenceStore>()
    every { prefs.getString(any()) } returns "https://gitlab.example.com"
    val resp = mockk<HttpResponse<String>>()
    every { resp.statusCode() } returns 200
    every { resp.body() } returns """{"id":7,"username":"me"}"""
    every { http.send(any()) } returns resp
    val client = GitLabApiClient(http, tokens, prefs)
    val user = client.fetchObject("/user", type = GitLabUser::class.java)
    user.id shouldBe 7L
    user.username shouldBe "me"
  }
})
