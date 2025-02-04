package com.gitlab.eclipse.chat

import com.gitlab.eclipse.chat.utils.refreshDuoChatWindow
import com.gitlab.eclipse.lsp.FeatureStateChange
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.utils.currentDisplay
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.commands.ICommandService
import org.eclipse.ui.internal.Workbench

class DuoChatStateServiceTest : DescribeSpec({
  val commandService = mockk<ICommandService>(relaxed = true)
  val workbench = mockk<Workbench>(relaxed = true)

  var duoChatStateService = DuoChatStateService()

  beforeSpec {
    mockkStatic("com.gitlab.eclipse.chat.utils.DuoChatWindowKt")
    mockkStatic("com.gitlab.eclipse.utils.DisplayKt")

    mockkStatic(PlatformUI::class)
  }

  beforeEach {
    every { refreshDuoChatWindow() } returns Unit

    every {
      currentDisplay.syncExec(any())
    } answers {
      firstArg<Runnable>().run()
    }

    every { PlatformUI.getWorkbench() } returns workbench
    every { workbench.getService(ICommandService::class.java) } returns commandService

    duoChatStateService = DuoChatStateService()
  }

  afterEach { clearAllMocks() }

  afterSpec { unmockkAll() }

  describe("update") {
    it("should update state and refresh duo chat changed when state changes") {
      val check = FeatureStateChangeCheck(checkId = "test", engaged = false)
      val featureStateChange = FeatureStateChange(
        featureId = "chat",
        allChecks = listOf(check)
      )

      duoChatStateService.update(featureStateChange)

      duoChatStateService.currentState[DuoChatStateService.DUO_CHAT_ENABLED_KEY] shouldBe true
      verify(exactly = 1) { refreshDuoChatWindow() }
      verify(exactly = 1) { commandService.refreshElements("gitlab-eclipse-plugin.commands.chatStatus", any()) }
    }

    it("should not refresh window when state doesn't change") {
      val check = FeatureStateChangeCheck(checkId = "test", engaged = true)
      val featureStateChange = FeatureStateChange(
        featureId = "chat",
        allChecks = listOf(check)
      )

      duoChatStateService.update(featureStateChange)

      duoChatStateService.currentState[DuoChatStateService.DUO_CHAT_ENABLED_KEY] shouldBe false
      verify(exactly = 0) { commandService.refreshElements("gitlab-eclipse-plugin.commands.chatStatus", any()) }
      verify(exactly = 0) { refreshDuoChatWindow() }
    }
  }

  describe("getFirstEngagedCheck") {
    it("should return first engaged check") {
      val check1 = FeatureStateChangeCheck(checkId = "test", engaged = true)
      val check2 = FeatureStateChangeCheck(checkId = "test", engaged = false)
      val featureStateChange = FeatureStateChange(featureId = "chat", allChecks = listOf(check1, check2))

      duoChatStateService.update(featureStateChange)

      duoChatStateService.getFirstEngagedCheck() shouldBe check1
    }

    it("should return null when no checks are engaged") {
      val check = FeatureStateChangeCheck(checkId = "test", engaged = false)
      val featureStateChange = FeatureStateChange(
        featureId = "chat",
        allChecks = listOf(check)
      )

      duoChatStateService.update(featureStateChange)

      duoChatStateService.getFirstEngagedCheck() shouldBe null
    }
  }
})
