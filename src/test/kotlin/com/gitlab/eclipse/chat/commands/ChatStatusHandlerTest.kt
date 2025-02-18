package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.utils.theming.ThemeUtils
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.*
import org.eclipse.jface.resource.ImageDescriptor
import org.eclipse.ui.menus.UIElement
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class ChatStatusHandlerTest : DescribeSpec({
  val element = mockk<UIElement>(relaxUnitFun = true)
  val duoChatStatusService = mockk<DuoChatStateService>()
  val handler = ChatStatusHandler()

  beforeSpec {
    mockkObject(ThemeUtils)

    startKoin {
      modules(
        module {
          single<DuoChatStateService> { duoChatStatusService }
        }
      )
    }
  }

  beforeEach {
    every { ThemeUtils.getThemedIcon(any()) } returns mockk<ImageDescriptor>()
  }

  afterEach { clearAllMocks() }

  afterSpec {
    unmockkAll()
    stopKoin()
  }

  describe("updateElement") {
    it("should show enabled status when chat has no engaged check") {
      every { duoChatStatusService.getFirstEngagedCheck() } returns null
      handler.updateElement(element, mutableMapOf())

      verify {
        ThemeUtils.getThemedIcon("chaton_obj")
        element.setText("Duo Chat: Enabled")
        element.setIcon(any())
      }
    }

    it("should show disabled status with a chat check is engaged") {
      every { duoChatStatusService.getFirstEngagedCheck() } returns FeatureStateChangeCheck(
        checkId = "authentication-required",
        engaged = true
      )
      handler.updateElement(element, mutableMapOf())

      verify {
        ThemeUtils.getThemedIcon("chatoff_obj")
        element.setText("Duo Chat: Disabled (authentication-required)")
        element.setIcon(any())
      }
    }
  }
})
