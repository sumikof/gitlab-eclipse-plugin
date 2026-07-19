package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.api.http.GitLabHttpClientFactory
import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.mockk
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class ApiModuleTest : DescribeSpec({
  describe("apiModule") {
    it("resolves all api services given their external dependencies") {
      val externals = module {
        single<ScopedPreferenceStore> { mockk(relaxed = true) }
        single<LanguageServerProxyManager> { mockk(relaxed = true) }
        single { mockk<com.gitlab.eclipse.authentication.GitLabTokenProviderManager>(relaxed = true) }
      }
      val app = koinApplication { modules(externals, apiModule) }
      // Smoke-resolve the top of the graph.
      app.koin.get<IssueService>()
      app.koin.get<GitLabApiClient>()
      app.koin.get<GitLabHttpClient>()
      app.koin.get<GitLabHttpClientFactory>()
    }
  }
})
