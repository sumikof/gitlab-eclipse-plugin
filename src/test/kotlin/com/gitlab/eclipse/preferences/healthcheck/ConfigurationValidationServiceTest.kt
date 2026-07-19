package com.gitlab.eclipse.preferences.healthcheck

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams
import com.gitlab.eclipse.lsp.utils.workspaceFolders
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.util.concurrent.CompletableFuture

class ConfigurationValidationServiceTest : DescribeSpec({
  val preferenceStore = mockk<ScopedPreferenceStore>(relaxed = true)
  val languageServer = mockk<GitLabLanguageServer>()
  val wrapper = mockk<GitLabLanguageServerWrapper>()

  val service = ConfigurationValidationService(preferenceStore, wrapper)

  extensions(LoggingKotestExtension)

  beforeSpec {
    // `workspaceFolders` is a top-level val that calls ResourcesPlugin.getWorkspace();
    // it must be mocked or every test throws in a plain-JVM run.
    mockkStatic("com.gitlab.eclipse.lsp.utils.ProjectsWorkspaceFolderKt")
  }

  beforeEach {
    every { workspaceFolders } returns emptyList()
    every { wrapper.languageServer } returns languageServer
    every { preferenceStore.getString(any()) } returns ""
    every { preferenceStore.getBoolean(any()) } returns false
    every { languageServer.validateConfiguration(any()) } returns
      CompletableFuture.completedFuture(emptyList())
  }

  afterEach { clearAllMocks() }
  afterSpec { unmockkAll() }

  fun capturedParams(): GitLabLanguageServerConfigurationParams {
    val captured = slot<GitLabLanguageServerConfigurationParams>()
    verify { languageServer.validateConfiguration(capture(captured)) }
    return captured.captured
  }

  describe("httpAgentOptions") {
    it("forwards client cert and key paths when set") {
      every { preferenceStore.getString(PreferenceConstants.CA_CERTIFICATE) } returns "/tmp/ca.pem"
      every { preferenceStore.getString(PreferenceConstants.CLIENT_CERTIFICATE) } returns "/tmp/client.pem"
      every { preferenceStore.getString(PreferenceConstants.CLIENT_CERTIFICATE_KEY) } returns "/tmp/client.key"

      runTest {
        service.validateConfiguration(ConfigurationValidationRequest(baseUrl = "https://gitlab.example.com", token = "token"))
      }

      val opts = capturedParams().httpAgentOptions!!
      opts.ca shouldBe "/tmp/ca.pem"
      opts.cert shouldBe "/tmp/client.pem"
      opts.certKey shouldBe "/tmp/client.key"
    }

    it("nulls out blank cert and key") {
      every { preferenceStore.getString(PreferenceConstants.CLIENT_CERTIFICATE) } returns ""
      every { preferenceStore.getString(PreferenceConstants.CLIENT_CERTIFICATE_KEY) } returns "   "

      runTest {
        service.validateConfiguration(ConfigurationValidationRequest(baseUrl = "https://gitlab.example.com", token = "token"))
      }

      val opts = capturedParams().httpAgentOptions!!
      opts.cert.shouldBeNull()
      opts.certKey.shouldBeNull()
    }
  }
})
