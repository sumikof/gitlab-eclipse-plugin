package com.gitlab.eclipse.lsp.configuration

import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.codesuggestions.languages.CodeSuggestionsLanguageService
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.utils.workspaceFolders
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class GitLabLanguageServerConfigurationServiceTest : DescribeSpec({
  val preferenceStore = mockk<ScopedPreferenceStore>(relaxed = true)
  val languageServer = mockk<GitLabLanguageServer>(relaxUnitFun = true)
  val wrapper = mockk<GitLabLanguageServerWrapper>()
  val languageService = mockk<CodeSuggestionsLanguageService>()
  val tokenManager = mockk<GitLabTokenProviderManager>()

  val service = GitLabLanguageServerConfigurationService(
    preferenceStore,
    wrapper,
    CoroutineScope(Dispatchers.Unconfined)
  )

  extensions(LoggingKotestExtension)

  beforeSpec {
    // `workspaceFolders` is a top-level val that calls ResourcesPlugin.getWorkspace();
    // it must be mocked or every test throws in a plain-JVM run.
    mockkStatic("com.gitlab.eclipse.lsp.utils.ProjectsWorkspaceFolderKt")
    startKoin {
      modules(
        module {
          single { languageService }
          single { tokenManager }
        },
      )
    }
  }

  beforeEach {
    every { workspaceFolders } returns emptyList()
    every { wrapper.languageServer } returns languageServer
    every { languageService.getAdditionalLanguages() } returns emptyList()
    every { languageService.getDisabledLanguages() } returns emptyList()
    every { tokenManager.getToken() } returns "token"
    // sensible defaults; individual tests override
    every { preferenceStore.getString(any()) } returns ""
    every { preferenceStore.getBoolean(any()) } returns false
  }

  afterEach { clearAllMocks() }
  afterSpec {
    stopKoin()
    unmockkAll()
  }

  fun capturedParams(): GitLabLanguageServerConfigurationParams {
    val captured = slot<DidChangeConfigurationParams>()
    verify { languageServer.didChangeConfiguration(capture(captured)) }
    return captured.captured.settings as GitLabLanguageServerConfigurationParams
  }

  describe("httpAgentOptions") {
    it("forwards client cert and key paths when set") {
      every { preferenceStore.getString(PreferenceConstants.CA_CERTIFICATE) } returns "/tmp/ca.pem"
      every { preferenceStore.getString(PreferenceConstants.CLIENT_CERTIFICATE) } returns "/tmp/client.pem"
      every { preferenceStore.getString(PreferenceConstants.CLIENT_CERTIFICATE_KEY) } returns "/tmp/client.key"

      service.sendConfiguration()

      val opts = capturedParams().httpAgentOptions!!
      opts.ca shouldBe "/tmp/ca.pem"
      opts.cert shouldBe "/tmp/client.pem"
      opts.certKey shouldBe "/tmp/client.key"
    }

    it("nulls out blank cert and key") {
      every { preferenceStore.getString(PreferenceConstants.CLIENT_CERTIFICATE) } returns ""
      every { preferenceStore.getString(PreferenceConstants.CLIENT_CERTIFICATE_KEY) } returns "   "

      service.sendConfiguration()

      val opts = capturedParams().httpAgentOptions!!
      opts.cert shouldBe null
      opts.certKey shouldBe null
    }
  }

  describe("codeCompletion.enabled") {
    it("forwards the global enabled preference") {
      every { preferenceStore.getBoolean(PreferenceConstants.CODE_SUGGESTIONS_ENABLED) } returns true

      service.sendConfiguration()

      capturedParams().codeCompletion!!.enabled shouldBe true
    }

    it("forwards disabled when the preference is off") {
      every { preferenceStore.getBoolean(PreferenceConstants.CODE_SUGGESTIONS_ENABLED) } returns false

      service.sendConfiguration()

      capturedParams().codeCompletion!!.enabled shouldBe false
    }
  }

  describe("duo settings") {
    it("forwards the duo chat toggle") {
      every { preferenceStore.getBoolean(PreferenceConstants.DUO_CHAT_ENABLED) } returns true

      service.sendConfiguration()

      capturedParams().duoChat!!.enabled shouldBe true
    }

    it("forwards the enabled-without-project toggle") {
      every { preferenceStore.getBoolean(PreferenceConstants.DUO_ENABLED_WITHOUT_GITLAB_PROJECT) } returns true

      service.sendConfiguration()

      capturedParams().duo!!.enabledWithoutGitlabProject shouldBe true
    }

    it("forwards the toggles when they are switched off") {
      every { preferenceStore.getBoolean(PreferenceConstants.DUO_CHAT_ENABLED) } returns false
      every { preferenceStore.getBoolean(PreferenceConstants.DUO_ENABLED_WITHOUT_GITLAB_PROJECT) } returns false

      service.sendConfiguration()

      capturedParams().duoChat!!.enabled shouldBe false
      capturedParams().duo!!.enabledWithoutGitlabProject shouldBe false
    }
  }
})
