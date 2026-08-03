package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.api.http.GitLabHttpClientFactory
import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
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

    it("resolves the GraphQL client and discussion service given their external dependencies") {
      val app = koinApplication { modules(externalsModule(), apiModule) }
      app.koin.get<GitLabGraphQlClient>()
      app.koin.get<DiscussionService>()
    }

    it("resolves DiscussionService as the same single instance on repeated lookups") {
      val app = koinApplication { modules(externalsModule(), apiModule) }
      app.koin.get<DiscussionService>() shouldBeSameInstanceAs app.koin.get<DiscussionService>()
    }

    it("resolves DiscussionWriteService as the same single instance on repeated lookups") {
      val app = koinApplication { modules(externalsModule(), apiModule) }
      app.koin.get<DiscussionWriteService>() shouldBeSameInstanceAs app.koin.get<DiscussionWriteService>()
    }

    it("satisfies DiscussionService's service() constructor default from the global Koin context") {
      startKoin { modules(externalsModule(), apiModule) }
      try {
        // Must not throw: the `graphQlClient: GitLabGraphQlClient = service()` default resolves.
        DiscussionService()
      } finally {
        stopKoin()
      }
    }
  }
})

/** The same external (non-api-module) dependencies the existing smoke test stubs inline. */
private fun externalsModule() = module {
  single<ScopedPreferenceStore> { mockk(relaxed = true) }
  single<LanguageServerProxyManager> { mockk(relaxed = true) }
  single { mockk<com.gitlab.eclipse.authentication.GitLabTokenProviderManager>(relaxed = true) }
}
