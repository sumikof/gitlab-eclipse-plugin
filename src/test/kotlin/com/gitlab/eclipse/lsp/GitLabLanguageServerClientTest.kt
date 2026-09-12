package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.chat.ChatAvailabilityService
import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.chat.context.EditorSelectionContextProvider
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.capabilities.DidChangeWatchedFileCapability
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticMarkerService
import com.gitlab.eclipse.lsp.edit.WorkspaceEditApplier
import com.gitlab.eclipse.lsp.git.GitDiffService
import com.gitlab.eclipse.lsp.messages.CopyTextParams
import com.gitlab.eclipse.lsp.messages.EditorSelectionContext
import com.gitlab.eclipse.lsp.messages.GitDiffParams
import com.gitlab.eclipse.lsp.messages.OpenFileParams
import com.gitlab.eclipse.lsp.plugins.PluginMessageService
import com.gitlab.eclipse.lsp.plugins.messages.PluginMessage
import com.gitlab.eclipse.lsp.plugins.messages.WebViewMessage
import com.gitlab.eclipse.security.CommandWaiters
import com.gitlab.eclipse.security.SecurityScanResponse
import com.gitlab.eclipse.security.SecurityScanStatusReporter
import com.gitlab.eclipse.utils.NotificationUtils
import com.google.gson.JsonObject
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.*
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Registration
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowDocumentParams
import org.eclipse.lsp4j.ShowDocumentResult
import org.eclipse.lsp4j.Unregistration
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.services.GenericEndpoint
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.osgi.framework.Bundle
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GitLabLanguageServerClientTest : DescribeSpec({
  val didChangeWatchedFilesCapability = mockk<DidChangeWatchedFileCapability>(relaxUnitFun = true)

  val gitDiffService = mockk<GitDiffService>(relaxUnitFun = true)

  val duoChatStateService = mockk<DuoChatStateService>(relaxUnitFun = true)
  val chatAvailabilityService = mockk<ChatAvailabilityService>(relaxUnitFun = true)
  val codeSuggestionsStateService = mockk<CodeSuggestionsStateService>(relaxUnitFun = true)

  val editorSelectionContextProvider = mockk<EditorSelectionContextProvider>()

  val pluginMessageService = mockk<PluginMessageService>()

  val markerService = mockk<DiagnosticMarkerService>(relaxUnitFun = true)

  val editApplier = mockk<WorkspaceEditApplier>()
  val fileOpener = mockk<WorkspaceFileOpener>(relaxUnitFun = true)
  val copyTextHandler = mockk<CopyTextHandler>(relaxUnitFun = true)
  val showDocumentLauncher = mockk<ShowDocumentLauncher>()

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
          single<WorkspaceEditApplier> { editApplier }
          single<WorkspaceFileOpener> { fileOpener }
          single<CopyTextHandler> { copyTextHandler }
          single<ShowDocumentLauncher> { showDocumentLauncher }
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

  describe("plugin bus dispatch") {
    // Dispatch hops to another thread, so the connection that sent a message has to travel with it.
    // Each of the four entry points is checked on its own: one of them left holding null is exactly
    // the defect this parameter exists to prevent, and it would be silent.
    // [LanguageServerSession] declares no `equals`, so matching on `client.session` matches by
    // identity — a session belonging to some other connection would not satisfy it.
    fun busClient(): Pair<GitLabLanguageServerClient, PluginMessageService> {
      val service = mockk<PluginMessageService>()
      every { service.dispatch(any(), any(), any()) } returns CompletableFuture.completedFuture(null)
      return GitLabLanguageServerClient(service) to service
    }

    it("sends its own session with a plugin notification") {
      val (client, service) = busClient()

      client.gitlabPluginNotification(PluginMessage("duo-chat-v2", "appReady", null))

      verify(exactly = 1) { service.dispatch(any(), any(), client.session) }
    }

    it("sends its own session with a plugin request") {
      val (client, service) = busClient()

      client.gitlabPluginRequest(PluginMessage("duo-chat-v2", "getCurrentFileContext", null))

      verify(exactly = 1) { service.dispatch(any(), any(), client.session) }
    }

    it("sends its own session with a webview notification") {
      val (client, service) = busClient()

      client.gitlabWebviewNotification(WebViewMessage("duo-chat-v2", "appReady", null))

      verify(exactly = 1) { service.dispatch(any(), any(), client.session) }
    }

    it("sends its own session with a webview request") {
      val (client, service) = busClient()

      client.gitlabWebviewRequest(WebViewMessage("duo-chat-v2", "getCurrentFileContext", null))

      verify(exactly = 1) { service.dispatch(any(), any(), client.session) }
    }
  }
  describe("workspace/applyEdit") {
    val params = ApplyWorkspaceEditParams(WorkspaceEdit())

    // The one thing this handler must do. Exactly one party may answer an applyEdit request — the
    // UI runnable, the applier's internal start timeout, or a setup failure — so nothing may
    // complete, wrap or observe the applier's future from out here. A wrapper answering `false`
    // while a queued UI runnable still applies the edit makes the server write the file too, and
    // the same edits land twice.
    //
    // Identity alone is not enough to prove that: `CompletableFuture.orTimeout` returns `this`, so
    // a timeout added here would sail past a same-instance assertion. It does register a
    // `whenComplete` canceller on the future, which `getNumberOfDependents` sees — hence the second
    // assertion, which also catches `thenApply`, `handle` and any other dependent.
    it("returns the applier's own future, with nothing layered on it") {
      val answer = CompletableFuture<ApplyWorkspaceEditResponse>()
      every { editApplier.applyEdit(params) } returns answer

      val returned = client.applyEdit(params)

      returned shouldBeSameInstanceAs answer
      answer.numberOfDependents shouldBe 0
    }

    it("returns the applier's future unchanged for a request the applier declines") {
      val answer = CompletableFuture<ApplyWorkspaceEditResponse>()
      every { editApplier.applyEdit(params) } returns answer

      val returned = client.applyEdit(params)
      answer.complete(ApplyWorkspaceEditResponse(false))

      returned shouldBeSameInstanceAs answer
      returned.get().isApplied shouldBe false
    }

    // lsp4j has to be able to route the server's request here under its real method name. This is
    // also the only headless check that the override is not annotated a second time: `LanguageClient`
    // already carries `@JsonRequest("workspace/applyEdit")`, and a repeat makes `GenericEndpoint`
    // throw `IllegalStateException: Multiple methods for name workspace/applyEdit` as it is built.
    it("dispatches workspace/applyEdit to the applier through lsp4j") {
      every { editApplier.applyEdit(any()) } returns
        CompletableFuture.completedFuture(ApplyWorkspaceEditResponse(true))

      GenericEndpoint(client).request("workspace/applyEdit", params).get() shouldBe
        ApplyWorkspaceEditResponse(true)

      verify { editApplier.applyEdit(params) }
    }
  }

  describe("window/showDocument") {
    val uri = "https://gitlab.com/g/p/-/merge_requests/1"

    it("answers success for a URI the launcher opened") {
      every { showDocumentLauncher.show(uri) } returns CompletableFuture.completedFuture(true)

      client.showDocument(ShowDocumentParams(uri)).get().isSuccess shouldBe true
    }

    it("answers failure for a URI the launcher did not open") {
      every { showDocumentLauncher.show(uri) } returns CompletableFuture.completedFuture(false)

      client.showDocument(ShowDocumentParams(uri)).get().isSuccess shouldBe false
    }

    it("dispatches window/showDocument to the launcher through lsp4j") {
      every { showDocumentLauncher.show(any()) } returns CompletableFuture.completedFuture(true)

      GenericEndpoint(client).request("window/showDocument", ShowDocumentParams(uri)).get() shouldBe
        ShowDocumentResult(true)

      verify { showDocumentLauncher.show(uri) }
    }
  }

  describe("$/gitlab/openFile") {
    it("passes the params the server sent to the opener") {
      val params = OpenFileParams("/p/a.kt")

      client.gitlabOpenFile(params).join()

      verify { fileOpener.open(params) }
    }

    // Resolution probes the filesystem, which can block on a stalled network mount. That must not
    // stop lsp4j's dispatch thread from reading the next message, so the handler returns before the
    // opener has finished.
    it("returns before the opener has finished, keeping the dispatch thread free") {
      val started = CountDownLatch(1)
      val release = CountDownLatch(1)
      every { fileOpener.open(any()) } answers {
        started.countDown()
        release.await(WAIT_SECONDS, TimeUnit.SECONDS)
      }

      val returned = client.gitlabOpenFile(OpenFileParams("/p/a.kt"))
      try {
        started.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        returned.isDone shouldBe false
      } finally {
        release.countDown()
      }
      returned.join()
    }

    it("dispatches \$/gitlab/openFile to the opener through lsp4j") {
      val arrived = CountDownLatch(1)
      every { fileOpener.open(any()) } answers { arrived.countDown() }

      GenericEndpoint(client).notify("\$/gitlab/openFile", OpenFileParams("/p/a.kt"))

      arrived.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
      verify { fileOpener.open(OpenFileParams("/p/a.kt")) }
    }
  }

  describe("$/gitlab/copyText") {
    it("passes the params the server sent to the handler") {
      val params = CopyTextParams("some snippet")

      client.gitlabCopyText(params).join()

      verify { copyTextHandler.handle(params) }
    }

    // The handler queues the write on the UI thread and returns; nothing here may wait on it. A
    // waiter would turn a runnable dropped at workbench teardown into a hung dispatch thread.
    it("returns before the copy has finished, waiting on nothing") {
      val started = CountDownLatch(1)
      val release = CountDownLatch(1)
      every { copyTextHandler.handle(any()) } answers {
        started.countDown()
        release.await(WAIT_SECONDS, TimeUnit.SECONDS)
      }

      val returned = client.gitlabCopyText(CopyTextParams("some snippet"))
      try {
        started.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
        returned.isDone shouldBe false
      } finally {
        release.countDown()
      }
      returned.join()
    }

    it("dispatches \$/gitlab/copyText to the handler through lsp4j") {
      val arrived = CountDownLatch(1)
      every { copyTextHandler.handle(any()) } answers { arrived.countDown() }

      GenericEndpoint(client).notify("\$/gitlab/copyText", CopyTextParams("some snippet"))

      arrived.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
      verify { copyTextHandler.handle(CopyTextParams("some snippet")) }
    }
  }
})

/** How long a test waits for work that has been handed to another thread before calling it stuck. */
private const val WAIT_SECONDS = 5L
