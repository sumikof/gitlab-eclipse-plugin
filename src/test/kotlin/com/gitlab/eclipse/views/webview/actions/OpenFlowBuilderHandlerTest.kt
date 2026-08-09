package com.gitlab.eclipse.views.webview.actions

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.views.webview.WebviewEditorInput
import com.gitlab.eclipse.views.webview.WebviewEditorPart
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.eclipse.core.resources.IFile
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.eclipse.ui.IEditorPart
import org.eclipse.ui.IFileEditorInput
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.PartInitException
import org.eclipse.ui.PlatformUI
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.osgi.framework.Bundle
import java.net.URI

private const val FAILURE_MESSAGE = "Could not open the GitLab Flow Builder. See the Error Log."
private const val YAML_REQUIRED_MESSAGE = "Please open a YAML file to use the Flow Builder."
private const val SECRET_NAME = "secret-project.yml"
private const val SECRET_URI = "file:///home/alice/secret-project.yml"

class OpenFlowBuilderHandlerTest : DescribeSpec({
  val wrapper = mockk<GitLabLanguageServerWrapper>()

  /**
   * Records the message of every single-argument `error`, and nothing else.
   *
   * Every other message-carrying member of `ILog` — `log(IStatus)`, `info` / `warn` / `error` in
   * their `String` **and** `String, Throwable` forms — is left unstubbed on this strict mock, so
   * calling one throws out of the handler and fails the test. That is what makes the leak pin below
   * bite: matching the `Throwable` argument with `any()` would accept the very mutation design §17
   * names (Phase 4 PR-4, Codex P1-1: a `Bearer <token>` reached the Error Log through an attached
   * exception), because here the attached exception is the `PartInitException` whose message *is*
   * the path. The pattern is `AgenticChatWebViewClientTest.captureErrorLog`'s.
   */
  val logged = mutableListOf<String>()
  val log = mockk<ILog>()
  val page = mockk<IWorkbenchPage>(relaxUnitFun = true)
  val window = mockk<IWorkbenchWindow>()
  val workbench = mockk<IWorkbench>()

  fun activeEditorOn(name: String, locationUri: URI?) {
    val file = mockk<IFile>()
    every { file.locationURI } returns locationUri
    val input = mockk<IFileEditorInput>()
    every { input.file } returns file
    every { input.name } returns name
    val editor = mockk<IEditorPart>()
    every { editor.editorInput } returns input
    every { page.activeEditor } returns editor
  }

  beforeSpec {
    startKoin { modules(module { single<GitLabLanguageServerWrapper> { wrapper } }) }
    mockkStatic(Platform::class)
    mockkStatic(PlatformUI::class)
    mockkObject(NotificationUtils)
  }

  beforeEach {
    // Re-armed every test because `afterEach`'s `clearAllMocks()` drops answers as well as calls.
    logged.clear()
    val message = slot<String>()
    every { log.error(capture(message)) } answers { logged += message.captured }
    every { Platform.getLog(any<Bundle>()) } returns log
    every { NotificationUtils.show(any()) } just runs
    every { PlatformUI.getWorkbench() } returns workbench
    every { workbench.activeWorkbenchWindow } returns window
    every { window.activePage } returns page
    every { page.findEditors(any(), any(), any()) } returns emptyArray()
    every { page.openEditor(any(), any()) } returns mockk()
    activeEditorOn(SECRET_NAME, URI(SECRET_URI))
  }

  afterEach { clearAllMocks() }

  afterSpec {
    unmockkAll()
    stopKoin()
  }

  describe("execute") {
    it("opens the flow builder keyed by the YAML file the active editor is on") {
      OpenFlowBuilderHandler().execute(mockk())

      verify { page.openEditor(WebviewEditorInput.flowBuilder(SECRET_URI), WebviewEditorPart.EDITOR_ID) }
    }

    // Design §12: a missing active page is reported through both channels, not swallowed.
    it("tells the user when there is no active workbench page") {
      every { workbench.activeWorkbenchWindow } returns null

      OpenFlowBuilderHandler().execute(mockk())

      verify { NotificationUtils.show(FAILURE_MESSAGE) }
      verify(exactly = 0) { page.openEditor(any(), any()) }
    }

    // Design §7.3's precondition. `open_flow_builder.ts:5-8` shows a message and opens nothing.
    it("tells the user to open a YAML file when the active editor is not on one") {
      activeEditorOn("notes.txt", URI("file:///home/alice/notes.txt"))

      OpenFlowBuilderHandler().execute(mockk())

      verify { NotificationUtils.show(YAML_REQUIRED_MESSAGE) }
      verify(exactly = 0) { page.openEditor(any(), any()) }
    }

    // Design §12: `openOrReload` throws PartInitException and reports nothing itself.
    it("tells the user when the tab cannot be opened") {
      every { page.openEditor(any(), any()) } throws PartInitException("nope")

      OpenFlowBuilderHandler().execute(mockk())

      verify { NotificationUtils.show(FAILURE_MESSAGE) }
    }

    // Design §17: the file the command was run on reaches the key and neither channel.
    it("keeps the file it opened for out of the Error Log and out of the notification") {
      every { page.openEditor(any(), any()) } throws PartInitException(SECRET_URI)

      OpenFlowBuilderHandler().execute(mockk())

      // The handler did report the failure: an entry that never happened cannot leak, so without
      // this the next line would pass over an empty list.
      logged shouldHaveSize 1
      logged.single() shouldNotContain "secret-project"
      verify(exactly = 0) { NotificationUtils.show(match { it.contains("secret-project") }) }
    }
  }
})
