package com.gitlab.eclipse.chat.commands

import com.gitlab.eclipse.chat.DuoChatStateService
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.*
import org.eclipse.ui.menus.UIElement
import org.eclipse.ui.plugin.AbstractUIPlugin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class ChatStatusHandlerTest : DescribeSpec({
  val element = mockk<UIElement>(relaxUnitFun = true)
  val duoChatStatusService = mockk<DuoChatStateService>()
  val handler = ChatStatusHandler()

  beforeSpec {
    mockkStatic(AbstractUIPlugin::class)

    startKoin {
      modules(
        module {
          single<DuoChatStateService> { duoChatStatusService }
        }
      )
    }
  }

  beforeEach {
    every { AbstractUIPlugin.imageDescriptorFromPlugin(any(), any()) } returns null
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
        AbstractUIPlugin.imageDescriptorFromPlugin(
          "com.gitlab.eclipse.gitlab-eclipse-plugin",
          "icons/duo-chat.png"
        )
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
        AbstractUIPlugin.imageDescriptorFromPlugin(
          "com.gitlab.eclipse.gitlab-eclipse-plugin",
          "icons/duo-chat-off.png"
        )

        element.setText("Duo Chat: Disabled (authentication-required)")
        element.setIcon(any())
      }
    }
  }
})
