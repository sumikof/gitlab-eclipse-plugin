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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

@OptIn(ExperimentalCoroutinesApi::class)
class GitLabLanguageServerConfigurationServiceTest : DescribeSpec({
  val preferenceStore = mockk<ScopedPreferenceStore>(relaxed = true)
  val languageServer = mockk<GitLabLanguageServer>(relaxUnitFun = true)
  val wrapper = mockk<GitLabLanguageServerWrapper>()
  val languageService = mockk<CodeSuggestionsLanguageService>()
  val tokenManager = mockk<GitLabTokenProviderManager>()

  val service = GitLabLanguageServerConfigurationService(
    preferenceStore,
    wrapper,
    CoroutineScope(Dispatchers.Unconfined),
    Mutex()
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

  describe("server binding") {
    it("delivers the queued notification to the server captured at call time, not the wrapper's current one") {
      val serverA = mockk<GitLabLanguageServer>(relaxUnitFun = true)
      val serverB = mockk<GitLabLanguageServer>(relaxUnitFun = true)
      // StandardTestDispatcher queues the launch instead of running it inline, exposing
      // the gap between capturing the server and the coroutine actually sending.
      val testScope = TestScope(StandardTestDispatcher())
      val queuedService = GitLabLanguageServerConfigurationService(preferenceStore, wrapper, testScope, Mutex())

      queuedService.sendConfiguration(serverA)
      // A rapid second restart registers process B's proxy before the coroutine runs.
      every { wrapper.languageServer } returns serverB
      testScope.testScheduler.runCurrent()

      verify { serverA.didChangeConfiguration(any()) }
      verify(exactly = 0) { serverB.didChangeConfiguration(any()) }
    }
  }

  describe("send ordering") {
    it("sends the configuration as it stands when the send runs, not as it stood when queued") {
      // The Mutex serialises the sends but does not hand the lock out in the order it was asked
      // for, so a snapshot taken at call time can be transmitted after a newer one and leave the
      // server holding the older full configuration. Reading at send time removes the ordering
      // question: whichever coroutine wins the lock reads the store, which the toggle handlers
      // write BEFORE calling this (issue #16).
      val server = mockk<GitLabLanguageServer>(relaxUnitFun = true)
      // StandardTestDispatcher queues the launch, opening the window between the caller returning
      // and the notification actually going out.
      val testScope = TestScope(StandardTestDispatcher())
      val queuedService = GitLabLanguageServerConfigurationService(preferenceStore, wrapper, testScope, Mutex())
      every { preferenceStore.getBoolean(PreferenceConstants.CODE_SUGGESTIONS_ENABLED) } returns false

      queuedService.sendConfiguration(server)
      // The user toggles again before the queued coroutine gets to run.
      every { preferenceStore.getBoolean(PreferenceConstants.CODE_SUGGESTIONS_ENABLED) } returns true
      testScope.testScheduler.advanceUntilIdle()

      val captured = slot<DidChangeConfigurationParams>()
      verify { server.didChangeConfiguration(capture(captured)) }
      val params = captured.captured.settings as GitLabLanguageServerConfigurationParams
      params.codeCompletion?.enabled shouldBe true
    }

    it("does not cancel the shared scope when reading the configuration throws") {
      // Reading at send time moves `buildParams()` onto the coroutine, so what used to surface
      // synchronously at the caller now runs on the SHARED plain-Job scope from WorkspaceModule —
      // an escape there cancels every other coroutine on it.
      every { workspaceFolders } throws IllegalStateException("Workspace is closed.")
      val swallowUncaught = CoroutineExceptionHandler { _, _ -> }
      val sharedScope = CoroutineScope(Job() + UnconfinedTestDispatcher() + swallowUncaught)
      val scopedService = GitLabLanguageServerConfigurationService(preferenceStore, wrapper, sharedScope, Mutex())

      scopedService.sendConfiguration(languageServer)

      sharedScope.isActive shouldBe true
      verify(exactly = 0) { languageServer.didChangeConfiguration(any()) }
    }
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

  describe("securityScan settings") {
    it("keeps remoteSecurityScans false and securityScannerOptions disabled by default") {
      every { preferenceStore.getBoolean(PreferenceConstants.SECURITY_SCAN_ENABLED) } returns false

      service.sendConfiguration()

      val params = capturedParams()
      params.featureFlags!!.remoteSecurityScans shouldBe false
      params.securityScannerOptions!!.enabled shouldBe false
    }

    it("sends both gates as true when the master setting is on") {
      every { preferenceStore.getBoolean(PreferenceConstants.SECURITY_SCAN_ENABLED) } returns true

      service.sendConfiguration()

      val params = capturedParams()
      params.featureFlags!!.remoteSecurityScans shouldBe true
      params.securityScannerOptions!!.enabled shouldBe true
    }

    it("keeps both security scan gates false when the setting is never touched (A1)") {
      // No `every { preferenceStore.getBoolean(SECURITY_SCAN_ENABLED) } ...` stub here on purpose:
      // this proves the untouched-preference path, relying solely on the relaxed mock's
      // default `getBoolean` return value (false), same as a real ScopedPreferenceStore that
      // was never written to and falls back to the PreferenceInitializer default.
      service.sendConfiguration()

      val params = capturedParams()
      params.featureFlags!!.remoteSecurityScans shouldBe false
      params.securityScannerOptions!!.enabled shouldBe false
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
