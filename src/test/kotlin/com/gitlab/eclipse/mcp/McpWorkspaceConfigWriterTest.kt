package com.gitlab.eclipse.mcp

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.eclipse.core.internal.resources.Workspace
import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.IFolder
import org.eclipse.core.resources.IProject
import org.eclipse.core.resources.IWorkspace
import org.eclipse.core.resources.IWorkspaceRoot
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.runtime.CoreException
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.core.runtime.OperationCanceledException
import org.eclipse.core.runtime.Status
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

/**
 * A workspace whose projects are mocks over real temporary directories, so the writer's file
 * system work is real and its resource calls are recorded. Typed as the internal [Workspace]
 * because `WorkspaceJob`'s constructor casts `ResourcesPlugin.getWorkspace()` to it (same pattern
 * as `DuoTutorialWorkspaceFixture`).
 */
private class WriterFixture {
  val disk: Path = createTempDirectory("mcp-writer")
  val root: IWorkspaceRoot = mockk()
  val workspace: IWorkspace = mockk<Workspace>()
  var projects: Array<IProject> = emptyArray()
  val refreshed = mutableListOf<String>()

  init {
    mockkStatic(ResourcesPlugin::class)
    every { ResourcesPlugin.getWorkspace() } returns workspace
    every { root.contains(any()) } answers { firstArg<Any?>() === root }
    every { root.isConflicting(any()) } answers { firstArg<Any?>() === root }
    every { workspace.root } returns root
    every { root.projects } answers { projects }
  }

  fun project(name: String, open: Boolean = true): IProject {
    val directory = Files.createDirectories(disk.resolve(name))
    val gitlab: IFolder = mockk()
    every { gitlab.refreshLocal(any(), any()) } answers { refreshed += name }
    val file: IFile = mockk()
    every { file.name } returns "mcp.json"
    return mockk {
      every { this@mockk.name } returns name
      every { isOpen } returns open
      every { locationURI } returns directory.toUri()
      every { getFolder(".gitlab") } returns gitlab
      every { getFile(".gitlab/duo/mcp.json") } returns file
    }
  }

  fun configOf(name: String): Path = disk.resolve(name).resolve(".gitlab/duo/mcp.json")

  fun run(): McpWorkspaceConfigOutcome {
    val writer = McpWorkspaceConfigWriter(workspace)
    writer.runInWorkspace(NullProgressMonitor())
    return writer.outcome
  }
}

class McpWorkspaceConfigWriterTest : DescribeSpec({
  extension(LoggingKotestExtension)

  describe("runInWorkspace") {
    it("creates .gitlab/duo/mcp.json from the template in the first project and hands its file back") {
      val fixture = WriterFixture()
      val app = fixture.project("app")
      fixture.projects = arrayOf(app)

      val outcome = fixture.run()

      Files.readString(fixture.configOf("app")) shouldBe McpConfigService.DEFAULT_CONFIG_TEMPLATE
      outcome shouldBe McpWorkspaceConfigOutcome.Ready(
        file = app.getFile(".gitlab/duo/mcp.json"),
        configFile = fixture.configOf("app"),
        projectCount = 1,
        projectName = "app",
      )
      fixture.refreshed shouldBe listOf("app")
    }

    it("keeps an existing config byte for byte, however many times it runs") {
      val fixture = WriterFixture()
      fixture.projects = arrayOf(fixture.project("app"))
      Files.createDirectories(fixture.configOf("app").parent)
      Files.writeString(fixture.configOf("app"), "{ \"mcpServers\": { \"mine\": {} } }")

      repeat(2) { fixture.run().shouldBeInstanceOf<McpWorkspaceConfigOutcome.Ready>() }

      Files.readString(fixture.configOf("app")) shouldBe "{ \"mcpServers\": { \"mine\": {} } }"
    }

    it("creates the config once and leaves it alone on the next run") {
      val fixture = WriterFixture()
      fixture.projects = arrayOf(fixture.project("app"))

      fixture.run()
      Files.writeString(fixture.configOf("app"), "edited by the user")
      fixture.run()

      Files.readString(fixture.configOf("app")) shouldBe "edited by the user"
    }

    describe("when there is no usable first project") {
      it("reports that the workspace has no project, and writes nothing") {
        val fixture = WriterFixture()

        fixture.run() shouldBe McpWorkspaceConfigOutcome.Refused(McpWorkspaceConfigResolution.NoProject)
      }

      it("reports a closed first project, and writes into neither it nor the next one") {
        val fixture = WriterFixture()
        fixture.projects = arrayOf(fixture.project("Alpha", open = false), fixture.project("beta"))

        fixture.run() shouldBe McpWorkspaceConfigOutcome.Refused(McpWorkspaceConfigResolution.Closed("Alpha"))
        Files.exists(fixture.disk.resolve("Alpha/.gitlab")) shouldBe false
        Files.exists(fixture.disk.resolve("beta/.gitlab")) shouldBe false
      }
    }

    describe("when the config cannot be created") {
      it("reports a failure, without touching it, when mcp.json is a directory") {
        val fixture = WriterFixture()
        fixture.projects = arrayOf(fixture.project("app"))
        Files.createDirectories(fixture.configOf("app"))

        fixture.run() shouldBe McpWorkspaceConfigOutcome.Failed("app")
        Files.isDirectory(fixture.configOf("app")) shouldBe true
      }

      it("reports a failure, without touching it, when .gitlab is a regular file") {
        val fixture = WriterFixture()
        fixture.projects = arrayOf(fixture.project("app"))
        Files.writeString(fixture.disk.resolve("app/.gitlab"), "x")

        fixture.run() shouldBe McpWorkspaceConfigOutcome.Failed("app")
        Files.readString(fixture.disk.resolve("app/.gitlab")) shouldBe "x"
      }

      it("reports a failure when the workspace cannot be refreshed") {
        val fixture = WriterFixture()
        val app = fixture.project("app")
        every { app.getFolder(".gitlab").refreshLocal(any(), any()) } throws
          CoreException(Status.error("refresh failed"))
        fixture.projects = arrayOf(app)

        fixture.run() shouldBe McpWorkspaceConfigOutcome.Failed("app")
      }
    }

    it("reports a cancellation, not a failure, when the user cancels the refresh") {
      val fixture = WriterFixture()
      val app = fixture.project("app")
      every { app.getFolder(".gitlab").refreshLocal(any(), any()) } throws OperationCanceledException()
      fixture.projects = arrayOf(app)

      fixture.run() shouldBe McpWorkspaceConfigOutcome.Cancelled
    }

    it("writes nothing under the workspace root or into the user-scoped config") {
      val fixture = WriterFixture()
      val home = Files.createDirectories(fixture.disk.resolve("home"))
      fixture.projects = arrayOf(fixture.project("app"))

      val writer = McpWorkspaceConfigWriter(fixture.workspace, service = McpConfigService(homeDir = home))
      writer.runInWorkspace(NullProgressMonitor())

      // `disk` stands in for the workspace root: the projects are its children.
      Files.exists(fixture.disk.resolve(".gitlab")) shouldBe false
      Files.exists(home.resolve(".gitlab")) shouldBe false
      Files.exists(fixture.configOf("app")) shouldBe true
    }
  }

  describe("scheduling") {
    it("runs as a user job under the workspace root rule, so it is serialised with project changes") {
      val fixture = WriterFixture()

      val writer = McpWorkspaceConfigWriter(fixture.workspace)

      writer.rule shouldBe fixture.root
      writer.isUser shouldBe true
    }
  }
})
