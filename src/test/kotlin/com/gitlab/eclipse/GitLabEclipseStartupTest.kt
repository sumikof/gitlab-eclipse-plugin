package com.gitlab.eclipse

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.authentication.OAuthTokenProvider
import com.gitlab.eclipse.ci.joblog.JobLogGenerationRegistry
import com.gitlab.eclipse.codesuggestions.CodeSuggestionsManager
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServerProcessProvider
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticMarkerService
import com.gitlab.eclipse.security.SecurityScanSaveListener
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.osgi.framework.BundleContext

/**
 * The bundle's stop path, which is the last chance this plugin's diagnostics have to clean up.
 *
 * The steps before the clean up are ordinary service calls that can raise on a late or degraded
 * stop — Koin's scope may already be closed — and each of them sits *upstream* of work that must
 * happen: the markers this plugin published would otherwise survive in the Problems view, and the
 * save listener would stay attached to every `IDocumentProvider` it reached, all of which outlive
 * this bundle. So the property under test is not "stop does its work" but "stop does its work even
 * when an earlier step throws".
 */
class GitLabEclipseStartupTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val processProvider = mockk<GitLabLanguageServerProcessProvider>()
  val markerService = mockk<DiagnosticMarkerService>(relaxUnitFun = true)
  val saveListener = mockk<SecurityScanSaveListener>(relaxUnitFun = true)

  beforeSpec {
    startKoin {
      modules(
        module {
          single<GitLabLanguageServerProcessProvider> { processProvider }
          single<DiagnosticMarkerService> { markerService }
          single<SecurityScanSaveListener> { saveListener }
          single<CodeSuggestionsManager> { mockk(relaxed = true) }
          single<OAuthTokenProvider> { mockk(relaxed = true) }
          single<GitLabHttpClient> { mockk(relaxed = true) }
        }
      )
    }
  }

  afterSpec {
    stopKoin()
    // Both are process-wide objects this path writes to.
    DiagnosticGenerationRegistry.resetForTest()
    JobLogGenerationRegistry.active = true
  }

  describe("stop") {
    it("completes the diagnostics shutdown even when stopping the language server throws") {
      DiagnosticGenerationRegistry.resetForTest()
      // What a Koin scope that is already closed, or a language server that is half gone, looks
      // like from here.
      every { processProvider.stop() } throws IllegalStateException("Koin scope is already closed")

      shouldNotThrowAny { GitLabEclipseStartup().stop(mockk<BundleContext>()) }

      // Everything downstream of the throw still ran.
      DiagnosticGenerationRegistry.active shouldBe false
      verify { markerService.deleteAllMarkers() }
      verify { saveListener.uninstall() }
    }
  }
})
