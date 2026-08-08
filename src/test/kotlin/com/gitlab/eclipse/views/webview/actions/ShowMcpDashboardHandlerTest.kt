package com.gitlab.eclipse.views.webview.actions

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.views.webview.WebviewEditorInput
import com.gitlab.eclipse.views.webview.WebviewEditorPart
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.PartInitException
import org.eclipse.ui.PlatformUI
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.osgi.framework.Bundle

private const val FAILURE_MESSAGE = "Could not open the GitLab MCP Dashboard. See the Error Log."

class ShowMcpDashboardHandlerTest : DescribeSpec({
  val wrapper = mockk<GitLabLanguageServerWrapper>()
  val log = mockk<ILog>(relaxUnitFun = true)
  val page = mockk<IWorkbenchPage>(relaxUnitFun = true)
  val window = mockk<IWorkbenchWindow>()
  val workbench = mockk<IWorkbench>()

  beforeSpec {
    startKoin { modules(module { single<GitLabLanguageServerWrapper> { wrapper } }) }
    mockkStatic(Platform::class)
    mockkStatic(PlatformUI::class)
    mockkObject(NotificationUtils)
  }

  beforeEach {
    every { Platform.getLog(any<Bundle>()) } returns log
    every { NotificationUtils.show(any()) } just runs
    every { PlatformUI.getWorkbench() } returns workbench
    every { workbench.activeWorkbenchWindow } returns window
    every { window.activePage } returns page
    every { page.findEditors(any(), any(), any()) } returns emptyArray()
    every { page.openEditor(any(), any()) } returns mockk()
  }

  afterEach { clearAllMocks() }

  afterSpec {
    unmockkAll()
    stopKoin()
  }

  describe("execute") {
    it("opens the MCP dashboard on the active page") {
      ShowMcpDashboardHandler().execute(mockk())

      verify { page.openEditor(WebviewEditorInput.mcp(), WebviewEditorPart.EDITOR_ID) }
    }

    // Design §12: a missing active page is reported through both channels, not swallowed.
    it("tells the user when there is no active workbench page") {
      every { workbench.activeWorkbenchWindow } returns null

      ShowMcpDashboardHandler().execute(mockk())

      verify { NotificationUtils.show(FAILURE_MESSAGE) }
      verify(exactly = 0) { page.openEditor(any(), any()) }
    }

    // Design §12: `openOrReload` throws PartInitException and reports nothing itself.
    it("tells the user when the tab cannot be opened") {
      every { page.openEditor(any(), any()) } throws PartInitException("nope")

      ShowMcpDashboardHandler().execute(mockk())

      verify { NotificationUtils.show(FAILURE_MESSAGE) }
    }
  }
})
