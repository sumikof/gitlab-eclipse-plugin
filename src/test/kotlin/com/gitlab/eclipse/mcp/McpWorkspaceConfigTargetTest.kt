package com.gitlab.eclipse.mcp

import com.gitlab.eclipse.lsp.utils.workspaceFoldersOf
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.eclipse.core.resources.IProject
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory

/** A project mock with the three facts the resolver reads. */
private fun project(name: String, location: URI?, open: Boolean = true): IProject = mockk {
  every { this@mockk.name } returns name
  every { locationURI } returns location
  every { isOpen } returns open
}

private fun projectDirectory(root: Path, name: String): Path = Files.createDirectories(root.resolve(name))

class McpWorkspaceConfigTargetTest : DescribeSpec({
  val target = McpWorkspaceConfigTarget()

  describe("resolve") {
    it("targets the project's own .gitlab/duo/mcp.json when the workspace has one project") {
      val dir = projectDirectory(createTempDirectory("mcp-target"), "app")

      val app = project("app", dir.toUri())

      target.resolve(arrayOf(app)) shouldBe
        McpWorkspaceConfigResolution.Target(app, dir.resolve(".gitlab/duo/mcp.json"))
    }

    it("targets the first project when the workspace has several, whichever one the user is working in") {
      val root = createTempDirectory("mcp-target")
      val alpha = project("Alpha", projectDirectory(root, "Alpha").toUri())
      val beta = project("beta", projectDirectory(root, "beta").toUri())

      target.resolve(arrayOf(alpha, beta)) shouldBe
        McpWorkspaceConfigResolution.Target(alpha, root.resolve("Alpha/.gitlab/duo/mcp.json"))
    }

    it("targets the directory of the first workspace folder the language server is sent") {
      val root = createTempDirectory("mcp-target")
      // A space and a '#' are percent-encoded in the URI the server receives; it decodes them again.
      val first = project("my app", projectDirectory(root, "my app #1").toUri())
      val second = project("other", projectDirectory(root, "other").toUri())
      val projects = arrayOf(first, second)

      val resolution = target.resolve(projects) as McpWorkspaceConfigResolution.Target

      resolution.configFile shouldBe
        Paths.get(URI(workspaceFoldersOf(projects).first().uri)).resolve(".gitlab/duo/mcp.json")
    }

    it("has nowhere to put the config when the workspace has no project") {
      target.resolve(emptyArray()) shouldBe McpWorkspaceConfigResolution.NoProject
    }

    it("refuses a closed first project instead of falling back to the next one the server does not read") {
      val root = createTempDirectory("mcp-target")
      val closed = project("Alpha", projectDirectory(root, "Alpha").toUri(), open = false)
      val open = project("beta", projectDirectory(root, "beta").toUri())

      target.resolve(arrayOf(closed, open)) shouldBe McpWorkspaceConfigResolution.Closed("Alpha")
    }

    describe("a first project that is not a local directory the server can read") {
      it("has no location") {
        target.resolve(arrayOf(project("app", null))) shouldBe McpWorkspaceConfigResolution.Unavailable("app")
      }

      it("lives on another file system") {
        target.resolve(arrayOf(project("app", URI("rse://host/home/app")))) shouldBe
          McpWorkspaceConfigResolution.Unavailable("app")
      }

      it("is on a UNC share, whose URI the language server rejects, even though the share is reachable") {
        val everythingIsADirectory = McpWorkspaceConfigTarget(isDirectory = { true })

        everythingIsADirectory.resolve(arrayOf(project("app", URI("file:////server/share/app")))) shouldBe
          McpWorkspaceConfigResolution.Unavailable("app")
      }

      it("has a directory that no longer exists") {
        val gone = createTempDirectory("mcp-target").resolve("gone")

        target.resolve(arrayOf(project("app", gone.toUri()))) shouldBe McpWorkspaceConfigResolution.Unavailable("app")
      }
    }
  }
})
