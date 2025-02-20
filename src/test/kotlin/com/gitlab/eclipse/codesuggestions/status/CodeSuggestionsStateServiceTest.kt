package com.gitlab.eclipse.codesuggestions.status

import com.gitlab.eclipse.lsp.FeatureStateChange
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.utils.currentDisplay
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.commands.ICommandService
import org.eclipse.ui.internal.Workbench

class CodeSuggestionsStateServiceTest : DescribeSpec({
  val commandService = mockk<ICommandService>(relaxed = true)
  val workbench = mockk<Workbench>(relaxed = true)

  var codeSuggestionsStateService = CodeSuggestionsStateService()

  beforeSpec {
    mockkStatic("com.gitlab.eclipse.utils.DisplayKt")
    mockkStatic(PlatformUI::class)
  }

  beforeEach {
    every {
      currentDisplay.syncExec(any())
    } answers {
      firstArg<Runnable>().run()
    }

    every { PlatformUI.getWorkbench() } returns workbench
    every { workbench.getService(ICommandService::class.java) } returns commandService

    codeSuggestionsStateService = CodeSuggestionsStateService()
  }

  afterEach { clearAllMocks() }

  afterSpec { unmockkAll() }

  describe("update") {
    it("should update state and refresh code suggestions when state changes") {
      val check = FeatureStateChangeCheck(checkId = "test", engaged = false)
      val featureStateChange = FeatureStateChange(
        featureId = "code_suggestions",
        allChecks = listOf(check)
      )

      codeSuggestionsStateService.update(featureStateChange)

      codeSuggestionsStateService.isEnabled shouldBe true
      verify(exactly = 1) {
        commandService.refreshElements("gitlab-eclipse-plugin.commands.codeSuggestionsStatus", any())
      }
    }

    it("should not refresh when state doesn't change") {
      val check = FeatureStateChangeCheck(checkId = "test", engaged = true)
      val featureStateChange = FeatureStateChange(
        featureId = "code_suggestions",
        allChecks = listOf(check)
      )

      codeSuggestionsStateService.update(featureStateChange)

      codeSuggestionsStateService.isEnabled shouldBe false
      verify(exactly = 0) {
        commandService.refreshElements("gitlab-eclipse-plugin.commands.codeSuggestionsStatus", any())
      }
    }
  }

  describe("getFirstEngagedCheck") {
    it("should return first engaged check") {
      val check1 = FeatureStateChangeCheck(checkId = "test", engaged = true)
      val check2 = FeatureStateChangeCheck(checkId = "test", engaged = false)
      val featureStateChange = FeatureStateChange(
        featureId = "code_suggestions",
        allChecks = listOf(check1, check2)
      )

      codeSuggestionsStateService.update(featureStateChange)

      codeSuggestionsStateService.getFirstEngagedCheck() shouldBe check1
    }

    it("should return null when no checks are engaged") {
      val check = FeatureStateChangeCheck(checkId = "test", engaged = false)
      val featureStateChange = FeatureStateChange(
        featureId = "code_suggestions",
        allChecks = listOf(check)
      )

      codeSuggestionsStateService.update(featureStateChange)

      codeSuggestionsStateService.getFirstEngagedCheck() shouldBe null
    }
  }
})
