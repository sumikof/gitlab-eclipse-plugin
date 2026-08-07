package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.chat.ChatAvailabilityService
import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.chat.context.EditorSelectionContextProvider
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.capabilities.DidChangeWatchedFileCapability
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticMarkerService
import com.gitlab.eclipse.lsp.git.GitDiffService
import com.gitlab.eclipse.lsp.messages.EditorSelectionContext
import com.gitlab.eclipse.lsp.messages.GitDiffParams
import com.gitlab.eclipse.lsp.plugins.PluginMessageService
import com.gitlab.eclipse.security.CommandWaiters
import com.gitlab.eclipse.security.SecurityScanResponse
import com.gitlab.eclipse.security.SecurityScanStatusReporter
import com.gitlab.eclipse.utils.NotificationUtils
import com.google.gson.JsonObject
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.Unregistration
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.jsonrpc.services.GenericEndpoint
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.osgi.framework.Bundle
import java.util.concurrent.CompletableFuture

class GitLabLanguageServerClientTest : DescribeSpec({
  val didChangeWatchedFilesCapability = mockk<DidChangeWatchedFileCapability>(relaxUnitFun = true)

  val gitDiffService = mockk<GitDiffService>(relaxUnitFun = true)

  val duoChatStateService = mockk<DuoChatStateService>(relaxUnitFun = true)
  val chatAvailabilityService = mockk<ChatAvailabilityService>(relaxUnitFun = true)
  val codeSuggestionsStateService = mockk<CodeSuggestionsStateService>(relaxUnitFun = true)

  val editorSelectionContextProvider = mockk<EditorSelectionContextProvider>()

  val pluginMessageService = mockk<PluginMessageService>()

  val markerService = mockk<DiagnosticMarkerService>(relaxUnitFun = true)

  val client = GitLabLanguageServerClient(pluginMessageService)

  extensions(LoggingKotestExtension)

  beforeSpec {
    startKoin {
      modules(
        module {
          single<DuoChatStateService> { duoChatStateService }
          single<ChatAvailabilityService> { chatAvailabilityService }
          single<CodeSuggestionsStateService> { codeSuggestionsStateService }
          single<DidChangeWatchedFileCapability> { didChangeWatchedFilesCapability }
          single<GitDiffService> { gitDiffService }
          single<EditorSelectionContextProvider> { editorSelectionContextProvider }
          single<DiagnosticMarkerService> { markerService }
        }
      )
    }
  }

  afterEach { clearAllMocks() }

  afterSpec {
    unmockkAll()
    stopKoin()
  }

  describe("featureStateChange") {
    it("should update chat state based on the feature state") {
      val featureState = FeatureStateChange(
        featureId = "chat",
        allChecks = emptyList()
      )

      client.gitlabFeatureStateChange(arrayOf(featureState)).join()

      verify { duoChatStateService.update(featureState) }
      verify { chatAvailabilityService.updateClassic(featureState) }
    }

    it("should update agentic chat availability based on the feature state") {
      val featureState = FeatureStateChange(
        featureId = "agentic_chat",
        allChecks = emptyList()
      )

      client.gitlabFeatureStateChange(arrayOf(featureState)).join()

      verify { chatAvailabilityService.updateAgentic(featureState) }
      verify { duoChatStateService wasNot Called }
    }

    it("should update code suggestions state based on the feature state") {
      val featureState = FeatureStateChange(
        featureId = "code_suggestions",
        allChecks = emptyList()
      )

      client.gitlabFeatureStateChange(arrayOf(featureState)).join()

      verify { codeSuggestionsStateService.update(featureState) }
    }
  }

  describe("registerCapability") {
    it("should register didChangeWatchedFiles capability") {
      val registrationOptions = JsonObject()
      val registration = Registration(
        "test-id",
        "workspace/didChangeWatchedFiles",
        registrationOptions
      )
      val params = RegistrationParams(listOf(registration))

      client.registerCapability(params).join()

      verify { didChangeWatchedFilesCapability.register("test-id", registrationOptions) }
    }
  }

  describe("unregisterCapability") {
    it("should unregister didChangeWatchedFiles capability") {
      val unregistration = Unregistration(
        "test-id",
        "workspace/didChangeWatchedFiles",
      )
      val params = UnregistrationParams(listOf(unregistration))

      client.unregisterCapability(params).join()

      verify { didChangeWatchedFilesCapability.unregister("test-id") }
    }
  }

  describe("getGitDiff") {
    it("should get git diff with without specified branch") {
      val params = GitDiffParams(repositoryUri = "test/repo", branch = null)
      every { gitDiffService.getDiff(params.repositoryUri) } returns "test diff"

      val result = client.getGitDiff(params).join()

      result shouldBe "test diff"
    }

    it("should get git diff for specified branch") {
      val params = GitDiffParams(repositoryUri = "test/repo", branch = "test-branch")
      every { gitDiffService.getDiff(params.repositoryUri, params.branch) } returns "test branch diff"

      val result = client.getGitDiff(params).join()

      verify { gitDiffService.getDiff("test/repo", "test-branch") }
      result shouldBe "test branch diff"
    }

    it("should return null if getting git diff fails") {
      val params = GitDiffParams(repositoryUri = "test/repo")
      every { gitDiffService.getDiff(params.repositoryUri) } throws RuntimeException("test error")

      val result = client.getGitDiff(params).join()

      verify { gitDiffService.getDiff("test/repo") }
      result shouldBe null
    }
  }

  describe("publishDiagnostics") {
    val uri = "file:/p/a.kt"
    val key = "/p/a.kt"

    fun diagnosticFrom(source: String?) =
      Diagnostic(Range(Position(0, 0), Position(0, 1)), "boom").also { it.source = source }

    beforeEach { DiagnosticGenerationRegistry.resetForTest() }
    afterEach { DiagnosticGenerationRegistry.resetForTest() }

    // The language server publishes diagnostics as a plain LSP notification, so lsp4j must be able
    // to dispatch it, and the payload must reach the marker service. Asserting both at once keeps
    // this test able to fail: a no-op handler still dispatches fine.
    it("dispatches textDocument/publishDiagnostics to the marker service through lsp4j") {
      val client = GitLabLanguageServerClient(pluginMessageService)
      val diagnostic = diagnosticFrom("gitlab_secret_detection")

      GenericEndpoint(client).notify(
        "textDocument/publishDiagnostics",
        PublishDiagnosticsParams(uri, listOf(diagnostic))
      )

      verify { markerService.apply(key, listOf(diagnostic), 1L, 0L) }
    }

    it("applies an empty batch so that stale markers are fully replaced") {
      val client = GitLabLanguageServerClient(pluginMessageService)

      client.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))

      verify { markerService.apply(key, emptyList(), 1L, 0L) }
    }

    it("ignores diagnostics whose uri cannot be normalised") {
      val client = GitLabLanguageServerClient(pluginMessageService)

      client.publishDiagnostics(PublishDiagnosticsParams("untitled:Untitled-1", listOf(diagnosticFrom("s"))))

      verify(exactly = 0) { markerService.apply(any(), any(), any(), any()) }
    }

    it("does not apply anything when every diagnostic comes from a suspended source") {
      val client = GitLabLanguageServerClient(pluginMessageService)
      DiagnosticGenerationRegistry.suspendSource("sast", DiagnosticGenerationRegistry.nextSettingsSeq())

      client.publishDiagnostics(PublishDiagnosticsParams(uri, listOf(diagnosticFrom("sast"))))

      verify(exactly = 0) { markerService.apply(any(), any(), any(), any()) }
    }

    it("keeps diagnostics from sources that are still running") {
      val client = GitLabLanguageServerClient(pluginMessageService)
      DiagnosticGenerationRegistry.suspendSource("sast", DiagnosticGenerationRegistry.nextSettingsSeq())
      val suspended = diagnosticFrom("sast")
      val running = diagnosticFrom("secret_detection")

      client.publishDiagnostics(PublishDiagnosticsParams(uri, listOf(suspended, running)))

      verify { markerService.apply(key, listOf(running), 1L, 0L) }
    }

    it("does not apply anything once the connection epoch has moved on") {
      val client = GitLabLanguageServerClient(pluginMessageService)
      DiagnosticGenerationRegistry.onServerStopped()

      client.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))

      verify(exactly = 0) { markerService.apply(any(), any(), any(), any()) }
    }

    // The source can be suspended between the first check and the moment we are ready to apply.
    // The final re-check must then drop the whole batch instead of publishing markers we would
    // immediately have to clean up again.
    it("does not apply anything when the source is invalidated before the markers are scheduled") {
      val client = GitLabLanguageServerClient(pluginMessageService)
      mockkObject(DiagnosticGenerationRegistry)
      every { DiagnosticGenerationRegistry.isTokenValid(any()) } returns false

      try {
        client.publishDiagnostics(PublishDiagnosticsParams(uri, listOf(diagnosticFrom("sast"))))
      } finally {
        unmockkObject(DiagnosticGenerationRegistry)
      }

      verify(exactly = 0) { markerService.apply(any(), any(), any(), any()) }
    }

    it("swallows failures raised while handling the notification") {
      val client = GitLabLanguageServerClient(pluginMessageService)
      every { markerService.apply(any(), any(), any(), any()) } throws RuntimeException("boom")

      client.publishDiagnostics(PublishDiagnosticsParams(uri, listOf(diagnosticFrom("sast"))))

      verify { markerService.apply(key, any(), 1L, 0L) }
    }
  }

  describe("security scan response") {
    val path = "/p/a.kt"

    beforeEach {
      DiagnosticGenerationRegistry.resetForTest()
      CommandWaiters.resetForTest()
      SecurityScanStatusReporter.resetForTest()
      mockkObject(NotificationUtils)
      every { NotificationUtils.show(any()) } returns Unit
    }

    afterEach {
      unmockkObject(NotificationUtils)
      SecurityScanStatusReporter.resetForTest()
      CommandWaiters.resetForTest()
      DiagnosticGenerationRegistry.resetForTest()
    }

    // The server sends this as a plain notification, so lsp4j has to be able to dispatch it under
    // the exact method name. Asserting the notification as well keeps this able to fail: an
    // unimplemented handler dispatches perfectly well.
    it("dispatches the response notification through lsp4j") {
      val client = GitLabLanguageServerClient(pluginMessageService)
      CommandWaiters.add(path, 0L)

      GenericEndpoint(client).notify(
        "\$/gitlab/security/remoteSecurityScan/response",
        SecurityScanResponse(filePath = "file:$path", status = 200, results = emptyList())
      )

      verify { NotificationUtils.show("GitLab security scan: no issues found.") }
    }

    it("shows the fixed message and never the server's own error text") {
      val client = GitLabLanguageServerClient(pluginMessageService)
      CommandWaiters.add(path, 0L)

      client.securityScanResponse(
        SecurityScanResponse(filePath = path, status = 403, error = "Bearer glpat-SECRET")
      )

      verify {
        NotificationUtils.show(
          "GitLab security scan failed: the real-time scan is not available for this project or namespace."
        )
      }
      verify(exactly = 0) { NotificationUtils.show(match { it.contains("glpat") }) }
    }

    it("writes the audit line to the log without the error text or the absolute path") {
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      val client = GitLabLanguageServerClient(pluginMessageService)
      CommandWaiters.add(path, 0L)

      client.securityScanResponse(
        SecurityScanResponse(filePath = path, status = 500, error = "Bearer glpat-SECRET")
      )

      verify {
        log.info(
          "securityScan source=command outcome=failure httpStatus=500 findings=- exceptionType=- path=-"
        )
      }
    }

    it("stays silent about a save that succeeded") {
      val client = GitLabLanguageServerClient(pluginMessageService)

      client.securityScanResponse(
        SecurityScanResponse(filePath = path, status = 200, results = listOf("x"))
      )

      verify(exactly = 0) { NotificationUtils.show(any()) }
    }

    it("ignores a response that does not say which file it is about") {
      val client = GitLabLanguageServerClient(pluginMessageService)

      client.securityScanResponse(SecurityScanResponse(status = 500))

      verify(exactly = 0) { NotificationUtils.show(any()) }
    }

    it("ignores a response from a connection that has already been replaced") {
      val client = GitLabLanguageServerClient(pluginMessageService)
      CommandWaiters.add(path, 0L)
      DiagnosticGenerationRegistry.onServerStopped()

      client.securityScanResponse(SecurityScanResponse(filePath = path, status = 500))

      verify(exactly = 0) { NotificationUtils.show(any()) }
    }

    it("still tells the user when the audit line cannot be written") {
      // The record and the notification are independent obligations. Losing the log must not also
      // lose the answer the user is waiting for.
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      every { log.info(any<String>()) } throws RuntimeException("log is gone")
      val client = GitLabLanguageServerClient(pluginMessageService)
      CommandWaiters.add(path, 0L)

      client.securityScanResponse(SecurityScanResponse(filePath = path, status = 500))

      verify { NotificationUtils.show(any()) }
    }

    it("does not let a failing failure-log escape into the dispatch loop") {
      // The outermost handler runs on lsp4j's dispatch thread; anything thrown from here takes the
      // dispatcher with it, so the log call has to be contained just like the audit one (§16.2).
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      every { log.warn(any<String>()) } throws RuntimeException("log is gone")
      val client = GitLabLanguageServerClient(pluginMessageService)
      mockkObject(SecurityScanStatusReporter)
      every { SecurityScanStatusReporter.settle(any(), any(), any()) } throws RuntimeException("boom")

      try {
        shouldNotThrowAny {
          client.securityScanResponse(SecurityScanResponse(filePath = path, status = 500))
        }
      } finally {
        unmockkObject(SecurityScanStatusReporter)
      }

      verify { log.warn(any<String>()) }
    }

    it("swallows failures raised while handling the notification") {
      val client = GitLabLanguageServerClient(pluginMessageService)
      CommandWaiters.add(path, 0L)
      every { NotificationUtils.show(any()) } throws RuntimeException("no display")

      client.securityScanResponse(SecurityScanResponse(filePath = path, status = 500))

      verify { NotificationUtils.show(any()) }
    }
  }

  describe("editor selection") {
    it("returns the selection supplied by the provider") {
      every { editorSelectionContextProvider.provide() } returns
        CompletableFuture.completedFuture(EditorSelectionContext("a/main.kt", "def"))

      client.getEditorSelection().get() shouldBe EditorSelectionContext("a/main.kt", "def")
    }

    it("returns null when there is no selection") {
      every { editorSelectionContextProvider.provide() } returns CompletableFuture.completedFuture(null)

      client.getEditorSelection().get() shouldBe null
    }

    // The language server sends this request with no params at all, so lsp4j must be able
    // to dispatch it to a zero-argument method. This asserts that contract directly.
    it("dispatches through lsp4j when the request carries no params") {
      every { editorSelectionContextProvider.provide() } returns
        CompletableFuture.completedFuture(EditorSelectionContext("a/main.kt", "def"))

      val endpoint = GenericEndpoint(client)

      endpoint.request("\$/gitlab/ai-context/editor-selection", null).get() shouldBe
        EditorSelectionContext("a/main.kt", "def")
    }
  }
})
