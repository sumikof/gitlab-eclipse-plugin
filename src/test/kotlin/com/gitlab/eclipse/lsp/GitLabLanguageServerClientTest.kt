package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.capabilities.DidChangeWatchedFileCapability
import com.gitlab.eclipse.lsp.git.GitDiffService
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
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class GitLabLanguageServerClientTest : DescribeSpec({
  val didChangeWatchedFilesCapability = mockk<DidChangeWatchedFileCapability>(relaxUnitFun = true)

  val gitDiffService = mockk<GitDiffService>(relaxUnitFun = true)

  val duoChatStateService = mockk<DuoChatStateService>(relaxUnitFun = true)
  val codeSuggestionsStateService = mockk<CodeSuggestionsStateService>(relaxUnitFun = true)

  val codeSuggestionsApiStatusMonitor = mockk<CodeSuggestionsApiStatusService>(relaxUnitFun = true)
  val pluginMessageService = mockk<PluginMessageService>()

  val client = GitLabLanguageServerClient(codeSuggestionsApiStatusMonitor, pluginMessageService)

  extensions(LoggingKotestExtension)

  beforeSpec {
    startKoin {
      modules(
        module {
          single<DuoChatStateService> { duoChatStateService }
          single<CodeSuggestionsStateService> { codeSuggestionsStateService }
          single<DidChangeWatchedFileCapability> { didChangeWatchedFilesCapability }
          single<GitDiffService> { gitDiffService }
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
})
