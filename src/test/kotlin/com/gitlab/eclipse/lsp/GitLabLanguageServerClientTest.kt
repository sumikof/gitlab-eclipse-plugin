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
import com.google.gson.JsonObject
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
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
