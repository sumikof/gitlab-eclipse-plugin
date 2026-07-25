package com.gitlab.eclipse.lsp.webview

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.eclipse.e4.core.services.events.IEventBroker
import org.eclipse.jface.resource.FontRegistry
import org.eclipse.swt.graphics.Color
import org.eclipse.swt.graphics.FontData
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.themes.ITheme
import org.eclipse.ui.themes.IThemeManager
import org.osgi.service.event.EventHandler

@OptIn(ExperimentalCoroutinesApi::class)
class LanguageServerWebviewServiceTest : DescribeSpec({
  val languageServer = mockk<GitLabLanguageServer>(relaxUnitFun = true)
  val languageServerWrapper = mockk<GitLabLanguageServerWrapper>()
  val languageServerWebviewService = LanguageServerWebviewService(
    languageServerWrapper = languageServerWrapper,
    coroutineScope = TestScope(UnconfinedTestDispatcher())
  )
  val theme = mockk<ITheme>()
  val themeManager = mockk<IThemeManager>()
  val expectedColor = Color(255, 255, 255, 255)
  val fontRegistry = mockk<FontRegistry>()

  extensions(LoggingKotestExtension)

  beforeSpec {
    mockkStatic("com.gitlab.eclipse.utils.DisplayKt")
    mockkStatic(PlatformUI::class)
  }

  beforeEach {
    every { languageServerWrapper.languageServer } returns languageServer
    every { PlatformUI.getWorkbench().themeManager } returns themeManager
    every { themeManager.currentTheme } returns theme

    every { theme.colorRegistry.get(any()) } returns expectedColor

    every { theme.fontRegistry } returns fontRegistry
    every {
      fontRegistry.getFontData(eq("org.eclipse.jdt.ui.editors.textfont"))
    } returns arrayOf(FontData("Menlo", 12, 0))
    every {
      fontRegistry.getFontData(eq("org.eclipse.jface.headerfont"))
    } returns arrayOf(FontData("Lucida Grande", 14, 0))
  }

  afterEach {
    clearAllMocks()
  }

  afterSpec { unmockkAll() }

  describe("sendThemeChange") {
    it("should send the latest styles to the language server") {
      // when
      languageServerWebviewService.sendThemeChange()

      // Smoke test editor font, code font, and color styles were applied:
      val themeSlot = slot<ThemeChangedParams>()
      verify { languageServer.didChangeTheme(capture(themeSlot)) }
      themeSlot.captured.apply {
        styles["--editor-code-font-family"].shouldNotBeNull().shouldBeEqual("Menlo")
        styles["--editor-font-family"].shouldNotBeNull().shouldBeEqual("Lucida Grande")
        styles["--editor-textLink-foreground"].shouldNotBeNull().shouldBeEqual(expectedColor.css())
      }
    }

    it("delivers the queued theme change to the server captured at call time, not the wrapper's current one") {
      val serverA = mockk<GitLabLanguageServer>(relaxUnitFun = true)
      val serverB = mockk<GitLabLanguageServer>(relaxUnitFun = true)
      // StandardTestDispatcher queues the launch instead of running it inline, exposing
      // the gap between capturing the server and the coroutine actually sending.
      val testScope = TestScope(StandardTestDispatcher())
      val queuedService = LanguageServerWebviewService(languageServerWrapper, testScope)

      queuedService.sendThemeChange(serverA)
      // A rapid second restart registers process B's proxy before the coroutine runs.
      every { languageServerWrapper.languageServer } returns serverB
      testScope.testScheduler.runCurrent()

      verify { serverA.didChangeTheme(any()) }
      verify(exactly = 0) { serverB.didChangeTheme(any()) }
    }
  }

  describe("subscribeToThemeChanges") {
    it("should send the latest styles to the language server") {
      // given
      val eventBroker = mockk<IEventBroker>()
      every { PlatformUI.getWorkbench().getService(eq(IEventBroker::class.java)) } returns eventBroker
      val eventHandlerSlot = slot<EventHandler>()
      every { eventBroker.subscribe(any(), capture(eventHandlerSlot)) } returns true

      // when
      languageServerWebviewService.subscribeToThemeChanges()

      // then
      eventHandlerSlot.captured.handleEvent(mockk())
      val themeSlot = slot<ThemeChangedParams>()
      verify { languageServer.didChangeTheme(capture(themeSlot)) }
      themeSlot.captured.styles["--editor-textLink-foreground"].shouldNotBeNull().shouldBeEqual(expectedColor.css())
    }

    it("subscribes to the event broker only once") {
      val eventBroker = mockk<IEventBroker>()
      every { PlatformUI.getWorkbench().getService(eq(IEventBroker::class.java)) } returns eventBroker
      every { eventBroker.subscribe(any(), any<EventHandler>()) } returns true
      // The spec-level service instance is shared across tests and the test above has
      // already subscribed it; a fresh instance keeps this test independent.
      val service = LanguageServerWebviewService(
        languageServerWrapper = languageServerWrapper,
        coroutineScope = TestScope(UnconfinedTestDispatcher())
      )

      service.subscribeToThemeChanges()
      service.subscribeToThemeChanges()

      verify(exactly = 1) { eventBroker.subscribe(any(), any<EventHandler>()) }
    }

    it("retries on a later start when the broker rejects the subscription") {
      val eventBroker = mockk<IEventBroker>()
      every { PlatformUI.getWorkbench().getService(eq(IEventBroker::class.java)) } returns eventBroker
      // The broker rejects the first registration without throwing, then accepts.
      every { eventBroker.subscribe(any(), any<EventHandler>()) } returnsMany listOf(false, true)
      val service = LanguageServerWebviewService(
        languageServerWrapper = languageServerWrapper,
        coroutineScope = TestScope(UnconfinedTestDispatcher())
      )

      service.subscribeToThemeChanges()
      service.subscribeToThemeChanges()

      // A rejected registration must not latch the guard on: the next start retries.
      verify(exactly = 2) { eventBroker.subscribe(any(), any<EventHandler>()) }
    }
  }
})
