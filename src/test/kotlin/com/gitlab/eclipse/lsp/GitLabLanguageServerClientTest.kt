package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.capabilities.DidChangeWatchedFileCapability
import com.gitlab.eclipse.lsp.plugins.PluginMessageService
import com.google.gson.JsonObject
import io.kotest.core.spec.style.DescribeSpec
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

  val duoChatStateService = mockk<DuoChatStateService>(relaxUnitFun = true)
  val codeSuggestionsApiStatusMonitor = mockk<CodeSuggestionsApiStatusService>(relaxUnitFun = true)
  val pluginMessageService = mockk<PluginMessageService>()

  val client = GitLabLanguageServerClient(codeSuggestionsApiStatusMonitor, pluginMessageService)

  extensions(LoggingKotestExtension)

  beforeSpec {
    startKoin {
      modules(
        module {
          single<DuoChatStateService> { duoChatStateService }
          single<DidChangeWatchedFileCapability> { didChangeWatchedFilesCapability }
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
})
