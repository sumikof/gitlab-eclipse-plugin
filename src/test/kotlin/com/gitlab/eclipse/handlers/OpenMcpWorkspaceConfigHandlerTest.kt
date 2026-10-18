package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.mcp.McpWorkspaceConfigOpener
import com.gitlab.eclipse.mcp.McpWorkspaceConfigOutcome
import com.gitlab.eclipse.mcp.McpWorkspaceConfigResolution
import com.gitlab.eclipse.mcp.McpWorkspaceConfigWriter
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.IWorkbenchWindow
import java.nio.file.Path

/*
 * User-facing wording, spelled out rather than imported: the production constants would make a
 * test pass whatever they were changed to (same reasoning as DuoTutorialHandlerTest).
 */
private const val NO_PROJECT_TEXT = "Open a project to use the MCP workspace configuration."
private const val CLOSED_TEXT =
  "Project 'Alpha' is closed. GitLab Duo reads the MCP workspace configuration from the first project " +
    "in the workspace, sorted by name: open it and run the command again."
private const val UNAVAILABLE_TEXT =
  "Project 'app' is not on the local file system, so the MCP workspace configuration cannot be created in it."
private const val FAILED_TEXT = "Could not create the MCP workspace configuration in project 'app'. See the Error Log."

/**
 * The handler with the scheduler seam recording the writer and the completion callback instead of
 * running a job, so a spec decides which outcome the job "finished" with.
 */
private class HandlerFixture {
  val window: IWorkbenchWindow = mockk()
  val writer: McpWorkspaceConfigWriter = mockk()
  val opener: McpWorkspaceConfigOpener = mockk(relaxed = true)
  val notices = mutableListOf<String>()
  val scheduled = mutableListOf<Pair<McpWorkspaceConfigWriter, (McpWorkspaceConfigOutcome) -> Unit>>()

  val handler = OpenMcpWorkspaceConfigHandler(
    activeWindow = { window },
    newWriter = { writer },
    schedule = { writer, onDone -> scheduled += writer to onDone },
    opener = opener,
    notify = { notices += it },
  )

  fun finishWith(outcome: McpWorkspaceConfigOutcome) {
    handler.execute(ExecutionEvent())
    scheduled.single().second(outcome)
  }
}

class OpenMcpWorkspaceConfigHandlerTest : DescribeSpec({
  extension(LoggingKotestExtension)

  describe("execute") {
    it("only schedules the workspace job: no file or resource work on the UI thread") {
      val fixture = HandlerFixture()

      fixture.handler.execute(ExecutionEvent()).shouldBeNull()

      fixture.scheduled.map { it.first } shouldContainExactly listOf(fixture.writer)
      fixture.notices.shouldBeEmpty()
      verify(exactly = 0) { fixture.opener.open(any(), any()) }
    }

    it("hands a ready config to the opener, with the window the command was run from") {
      val fixture = HandlerFixture()
      val ready = McpWorkspaceConfigOutcome.Ready(mockk(), Path.of("/ws/app/.gitlab/duo/mcp.json"), 1, "app")

      fixture.finishWith(ready)

      verify(exactly = 1) { fixture.opener.open(fixture.window, ready) }
      fixture.notices.shouldBeEmpty()
    }

    describe("tells the user why nothing was opened") {
      it("when the workspace has no project") {
        val fixture = HandlerFixture()

        fixture.finishWith(McpWorkspaceConfigOutcome.Refused(McpWorkspaceConfigResolution.NoProject))

        fixture.notices shouldContainExactly listOf(NO_PROJECT_TEXT)
      }

      it("when the first project is closed") {
        val fixture = HandlerFixture()

        fixture.finishWith(McpWorkspaceConfigOutcome.Refused(McpWorkspaceConfigResolution.Closed("Alpha")))

        fixture.notices shouldContainExactly listOf(CLOSED_TEXT)
      }

      it("when the first project is not a local directory") {
        val fixture = HandlerFixture()

        fixture.finishWith(McpWorkspaceConfigOutcome.Refused(McpWorkspaceConfigResolution.Unavailable("app")))

        fixture.notices shouldContainExactly listOf(UNAVAILABLE_TEXT)
      }

      it("when the config could not be created") {
        val fixture = HandlerFixture()

        fixture.finishWith(McpWorkspaceConfigOutcome.Failed("app"))

        fixture.notices shouldContainExactly listOf(FAILED_TEXT)
      }
    }

    it("stays silent when the job was cancelled") {
      val fixture = HandlerFixture()

      fixture.finishWith(McpWorkspaceConfigOutcome.Cancelled)

      fixture.notices.shouldBeEmpty()
      verify(exactly = 0) { fixture.opener.open(any(), any()) }
    }
  }
})
