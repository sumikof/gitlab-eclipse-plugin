package com.gitlab.eclipse.mcp

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.eclipse.core.resources.IFile
import org.eclipse.swt.SWTException
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.PartInitException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

/*
 * User-facing wording, spelled out rather than imported: the production constants would make a
 * test pass whatever they were changed to (same reasoning as DuoTutorialHandlerTest).
 */
private const val OPEN_FAILED_TEXT = "Could not open the MCP workspace configuration. See the Error Log."

/** One open request as the editor seam saw it. */
private data class Opened(val window: IWorkbenchWindow, val file: IFile?, val store: Path?)

/**
 * The opener with every UI seam replaced by a recorder: `asyncExec` becomes [uiQueue] (drained by
 * [drainUi]), the editor open is recorded in [opened] and answers [openResult], notices go to
 * [notices].
 */
private class OpenerFixture {
  val uiQueue = ArrayDeque<Runnable>()
  val opened = mutableListOf<Opened>()
  val notices = mutableListOf<String>()
  var openResult: () -> Boolean = { true }
  var fallback: IWorkbenchWindow? = null
  val window: IWorkbenchWindow = mockk()
  val configFile: Path = createTempDirectory("mcp-opener").resolve(".gitlab/duo/mcp.json")
  var fileExists = true
  val file: IFile = mockk { every { exists() } answers { fileExists } }

  val opener = McpWorkspaceConfigOpener(
    onUiThread = { uiQueue.addLast(it) },
    fallbackWindow = { fallback },
    openEditor = { window, file, store ->
      opened += Opened(window, file, store)
      openResult()
    },
    notify = { notices += it },
  )

  fun ready(projectCount: Int = 1) = McpWorkspaceConfigOutcome.Ready(file, configFile, projectCount, "app")

  fun writeConfigOnDisk() {
    Files.createDirectories(configFile.parent)
    Files.writeString(configFile, "{}")
  }

  fun drainUi() {
    while (uiQueue.isNotEmpty()) uiQueue.removeFirst().run()
  }
}

class McpWorkspaceConfigOpenerTest : DescribeSpec({
  extension(LoggingKotestExtension)

  describe("open") {
    it("opens nothing until it is on the UI thread") {
      val fixture = OpenerFixture()

      fixture.opener.open(fixture.window, fixture.ready())

      fixture.opened.shouldBeEmpty()
      fixture.uiQueue.size shouldBe 1
    }

    it("opens the workspace file in the window the command was run from") {
      val fixture = OpenerFixture()

      fixture.opener.open(fixture.window, fixture.ready())
      fixture.drainUi()

      fixture.opened shouldContainExactly listOf(Opened(fixture.window, fixture.file, null))
      fixture.notices.shouldBeEmpty()
    }

    it("opens the file on disk when it is not a workspace resource (e.g. a resource filter hides .gitlab)") {
      val fixture = OpenerFixture()
      fixture.fileExists = false
      fixture.writeConfigOnDisk()

      fixture.opener.open(fixture.window, fixture.ready())
      fixture.drainUi()

      fixture.opened shouldContainExactly listOf(Opened(fixture.window, null, fixture.configFile))
    }

    it("reports that it could not open the config when it is gone by the time the UI gets to it") {
      val fixture = OpenerFixture()
      fixture.fileExists = false

      fixture.opener.open(fixture.window, fixture.ready())
      fixture.drainUi()

      fixture.opened.shouldBeEmpty()
      fixture.notices shouldContainExactly listOf(OPEN_FAILED_TEXT)
    }

    it("names the project it opened when the workspace has several, since that is not always the one in use") {
      val fixture = OpenerFixture()

      fixture.opener.open(fixture.window, fixture.ready(projectCount = 2))
      fixture.drainUi()

      fixture.notices shouldContainExactly listOf(
        "Opened the MCP workspace configuration of project 'app'. " +
          "GitLab Duo reads it from the first project in the workspace, sorted by name.",
      )
    }

    it("does not name the project when opening fails in a workspace with several") {
      val fixture = OpenerFixture()
      fixture.openResult = { false }

      fixture.opener.open(fixture.window, fixture.ready(projectCount = 2))
      fixture.drainUi()

      fixture.notices shouldContainExactly listOf(OPEN_FAILED_TEXT)
    }

    describe("never lets a failure into the UI event loop") {
      it("reports it when the window has no active page") {
        val fixture = OpenerFixture()
        fixture.openResult = { false }

        fixture.opener.open(fixture.window, fixture.ready())
        shouldNotThrowAny { fixture.drainUi() }

        fixture.notices shouldContainExactly listOf(OPEN_FAILED_TEXT)
      }

      it("reports it when the editor cannot be opened") {
        val fixture = OpenerFixture()
        fixture.openResult = { throw PartInitException("cannot open /home/me/secret/.gitlab/duo/mcp.json") }

        fixture.opener.open(fixture.window, fixture.ready())
        shouldNotThrowAny { fixture.drainUi() }

        fixture.notices shouldContainExactly listOf(OPEN_FAILED_TEXT)
      }

      it("reports it when anything else goes wrong while opening") {
        val fixture = OpenerFixture()
        fixture.openResult = { throw IllegalStateException("workbench is gone") }

        fixture.opener.open(fixture.window, fixture.ready())
        shouldNotThrowAny { fixture.drainUi() }

        fixture.notices shouldContainExactly listOf(OPEN_FAILED_TEXT)
      }

      it("opens in the active window when the command had none, and reports it when there is none either") {
        val fixture = OpenerFixture()
        val active: IWorkbenchWindow = mockk()
        fixture.fallback = active

        fixture.opener.open(null, fixture.ready())
        fixture.drainUi()
        fixture.fallback = null
        fixture.opener.open(null, fixture.ready())
        fixture.drainUi()

        fixture.opened shouldContainExactly listOf(Opened(active, fixture.file, null))
        fixture.notices shouldContainExactly listOf(OPEN_FAILED_TEXT)
      }

      it("gives up quietly when the UI thread cannot be reached (display disposed, workbench closing)") {
        listOf(SWTException(), IllegalStateException()).forEach { failure ->
          val notices = mutableListOf<String>()
          val opener = McpWorkspaceConfigOpener(
            onUiThread = { throw failure },
            fallbackWindow = { null },
            openEditor = { _, _, _ -> true },
            notify = { notices += it },
          )

          shouldNotThrowAny { opener.open(mockk(), OpenerFixture().ready()) }
          notices.shouldBeEmpty()
        }
      }
    }
  }
})
