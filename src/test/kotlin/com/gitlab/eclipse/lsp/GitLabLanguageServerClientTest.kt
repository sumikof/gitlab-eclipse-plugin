package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.chat.ChatAvailabilityService
import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.chat.context.EditorSelectionContextProvider
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.capabilities.DidChangeWatchedFileCapability
import com.gitlab.eclipse.lsp.git.GitDiffService
import com.gitlab.eclipse.lsp.messages.EditorSelectionContext
import com.gitlab.eclipse.lsp.messages.GitDiffParams
import com.gitlab.eclipse.lsp.plugins.PluginMessageService
import com.google.gson.JsonObject
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
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
