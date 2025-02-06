package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.plugins.PluginMessageService
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.*
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class GitLabLanguageServerClientTest : DescribeSpec({
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
})
