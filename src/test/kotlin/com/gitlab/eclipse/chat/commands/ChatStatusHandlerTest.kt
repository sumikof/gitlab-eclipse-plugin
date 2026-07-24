package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.ChatAvailability
import com.gitlab.eclipse.chat.ChatAvailabilityService
import com.gitlab.eclipse.chat.utils.openDuoChatWindow
import com.gitlab.eclipse.utils.system.SystemUtils
import com.gitlab.eclipse.utils.theming.IconTone
import com.gitlab.eclipse.utils.theming.ThemeUtils
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.eclipse.jface.resource.ImageDescriptor
import org.eclipse.ui.menus.UIElement
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class ChatStatusHandlerTest : DescribeSpec({
  val element = mockk<UIElement>(relaxUnitFun = true)
  val chatAvailabilityService = mockk<ChatAvailabilityService>()
  val handler by lazy { ChatStatusHandler() }

  beforeSpec {
    mockkObject(SystemUtils)
    mockkObject(ThemeUtils)
    mockkStatic("com.gitlab.eclipse.chat.utils.DuoChatWindowKt")

    startKoin {
      modules(
        module {
          single<ChatAvailabilityService> { chatAvailabilityService }
        }
      )
    }
  }

  beforeEach {
    every { ThemeUtils.getThemedIcon(any()) } returns mockk<ImageDescriptor>()
    every { ThemeUtils.getThemedIcon(any(), any()) } returns mockk<ImageDescriptor>()

    every { openDuoChatWindow() } returns Unit

    every { SystemUtils.isWindows() } returns false
  }

  afterEach { clearAllMocks() }

  afterSpec {
    unmockkAll()
    stopKoin()
  }

  it("should open duo chat window") {
    handler.execute(mockk())

    verify { openDuoChatWindow() }
  }

  // Agentic-only: the aggregate is true even though classic chat is disabled.
  it("should be enabled when any chat webview is enabled") {
    every { chatAvailabilityService.anyChatEnabled } returns true

    handler.isEnabled() shouldBe true
  }

  it("should be disabled when no chat webview is enabled") {
    every { chatAvailabilityService.anyChatEnabled } returns false

    handler.isEnabled() shouldBe false
  }

  describe("updateElement") {
    it("should always display dark icon on windows") {
      every { chatAvailabilityService.anyChatEnabled } returns true
      every { SystemUtils.isWindows() } returns true
      handler.updateElement(element, mutableMapOf())

      verify {
        ThemeUtils.getThemedIcon("chat_on_obj", IconTone.DARK)
        element.setText("Duo Chat: Enabled")
        element.setIcon(any())
      }
    }

    it("should show enabled status when any chat webview is enabled") {
      every { chatAvailabilityService.anyChatEnabled } returns true
      handler.updateElement(element, mutableMapOf())

      verify {
        ThemeUtils.getThemedIcon("chat_on_obj")
        element.setText("Duo Chat: Enabled")
        element.setIcon(any())
      }
    }

    it("should show disabled status with the classic disabled reason") {
      every { chatAvailabilityService.anyChatEnabled } returns false
      every { chatAvailabilityService.availabilityFor("duo-chat-v2") } returns ChatAvailability(
        id = "duo-chat-v2",
        enabled = false,
        disabledReason = "authentication-required"
      )
      handler.updateElement(element, mutableMapOf())

      verify {
        ThemeUtils.getThemedIcon("chat_off_obj")
        element.setText("Duo Chat: Disabled (authentication-required)")
        element.setIcon(any())
      }
    }

    it("should show plain disabled status when no reason is known") {
      every { chatAvailabilityService.anyChatEnabled } returns false
      every { chatAvailabilityService.availabilityFor("duo-chat-v2") } returns ChatAvailability(
        id = "duo-chat-v2",
        enabled = false,
        disabledReason = null
      )
      handler.updateElement(element, mutableMapOf())

      verify {
        ThemeUtils.getThemedIcon("chat_off_obj")
        element.setText("Duo Chat: Disabled")
        element.setIcon(any())
      }
    }
  }
})
