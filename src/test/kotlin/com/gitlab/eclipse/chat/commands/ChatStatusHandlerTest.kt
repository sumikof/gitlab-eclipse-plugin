package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.chat.utils.openDuoChatWindow
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
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
  val duoChatStatusService = mockk<DuoChatStateService>()
  val handler by lazy { ChatStatusHandler() }

  beforeSpec {
    mockkObject(SystemUtils)
    mockkObject(ThemeUtils)
    mockkStatic("com.gitlab.eclipse.chat.utils.DuoChatWindowKt")

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

  it("should be enabled only when chat is enabled") {
    every { duoChatStatusService.isEnabled } returns true
    handler.isEnabled() shouldBe true

    every { duoChatStatusService.isEnabled } returns false
    handler.isEnabled() shouldBe false
  }

  describe("updateElement") {
    it("should always display dark icon on windows") {
      every { duoChatStatusService.getFirstEngagedCheck() } returns null
      every { SystemUtils.isWindows() } returns true
      handler.updateElement(element, mutableMapOf())

      verify {
        ThemeUtils.getThemedIcon("chat_on_obj", IconTone.DARK)
        element.setText("Duo Chat: Enabled")
        element.setIcon(any())
      }
    }

    it("should show enabled status when chat has no engaged check") {
      every { duoChatStatusService.getFirstEngagedCheck() } returns null
      handler.updateElement(element, mutableMapOf())

      verify {
        ThemeUtils.getThemedIcon("chat_on_obj")
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
        ThemeUtils.getThemedIcon("chat_off_obj")
        element.setText("Duo Chat: Disabled (authentication-required)")
        element.setIcon(any())
      }
    }
  }
})
