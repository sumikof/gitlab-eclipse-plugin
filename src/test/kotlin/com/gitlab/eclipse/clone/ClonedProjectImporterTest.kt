package com.gitlab.eclipse.clone

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.eclipse.core.resources.IProject
import org.eclipse.core.resources.IProjectDescription
import org.eclipse.core.resources.IWorkspace
import org.eclipse.core.resources.IWorkspaceRoot
import org.eclipse.core.runtime.IPath
import java.io.File
import java.nio.file.Files

private fun pathOf(file: File): IPath = mockk<IPath> { every { toFile() } returns file }

private fun descriptionNamed(name: String): IProjectDescription =
  mockk<IProjectDescription>(relaxed = true) { every { this@mockk.name } returns name }

private fun tempParent(): File = Files.createTempDirectory("importer-dst").toFile()

private fun directoryUnder(parent: File, name: String): File = File(parent, name).also { it.mkdirs() }

/** Content is irrelevant — `loadProjectDescription` is stubbed; only `isFile` decides the branch. */
private fun writeDotProject(destination: File) {
  File(destination, IProjectDescription.DESCRIPTION_FILE_NAME).writeText("<projectDescription/>")
}

/**
 * The workspace as [ClonedProjectImporter] sees it. `IWorkspace`, `IWorkspaceRoot`, `IProject`,
 * `IProjectDescription` and `IPath` are plain Java interfaces — not SWT widgets — so the whole
 * import flow runs headlessly here, including the `open()`-failed compensation that cannot be
 * provoked on a real Eclipse.
 *
 * [root] hands out the same [project] mock for every name, matching the flow: it asks
 * `getProject(description.name)` once for the same-name check and again in `createAndOpen`.
 */
private class WorkspaceFixture(private val projectName: String) {
  val rootDir: File = Files.createTempDirectory("importer-ws").toFile()
  val project: IProject = mockk(relaxUnitFun = true)
  val root: IWorkspaceRoot = mockk()
  val workspace: IWorkspace = mockk()

  init {
    every { root.location } returns pathOf(rootDir)
    every { root.getProject(any()) } returns project
    every { workspace.root } returns root
    every { project.name } returns projectName
    every { project.exists() } returns false
    every { project.isOpen } returns false
    every { project.location } returns null
  }

  /** No `.project` on disk: Eclipse hands back a fresh description named after the folder. */
  fun withoutDotProject(folderName: String) {
    every { workspace.newProjectDescription(folderName) } returns descriptionNamed(projectName)
  }

  /** A `.project` on disk, naming a project that may differ from the folder it sits in. */
  fun withDotProject() {
    every { workspace.loadProjectDescription(any<IPath>()) } returns descriptionNamed(projectName)
  }

  /** A project already registered under the same name: open or closed, registered at [location]. */
  fun alreadyRegistered(open: Boolean, location: File?) {
    every { project.exists() } returns true
    every { project.isOpen } returns open
    every { project.location } returns location?.let { pathOf(it) }
  }

  fun importer(consent: Boolean = true): ClonedProjectImporter =
    ClonedProjectImporter(confirm = { consent }, workspace = { workspace })
}

class ClonedProjectImporterTest : StringSpec({
  // The failure paths log through Platform.getLog, which needs an OSGi bundle; this extension
  // stubs it the same way every other headless spec in this codebase does.
  extensions(LoggingKotestExtension)

  "imports a repository outside the workspace, creating then opening the project" {
    val ws = WorkspaceFixture("repo")
    val destination = directoryUnder(tempParent(), "repo")
    ws.withoutDotProject("repo")

    ws.importer().import(destination, RepositorySource.CLONED_NOW) shouldBe
      CloneOutcome.Imported(destination, "repo", RepositorySource.CLONED_NOW)

    verifyOrder {
      ws.project.create(any(), any())
      ws.project.open(any())
    }
  }

  "releases our closed orphan registered at this destination once the user consents" {
    val ws = WorkspaceFixture("repo")
    val destination = directoryUnder(tempParent(), "repo")
    ws.withoutDotProject("repo")
    ws.alreadyRegistered(open = false, location = destination)

    ws.importer(consent = true).import(destination, RepositorySource.CLONED_NOW) shouldBe
      CloneOutcome.Imported(destination, "repo", RepositorySource.CLONED_NOW)

    // Registration only: the clone's files stay on disk.
    verify { ws.project.delete(false, true, null) }
    verify(exactly = 0) { ws.project.delete(true, any(), any()) }
  }

  "declined consent leaves the orphan registered and reports it as a leftover" {
    val ws = WorkspaceFixture("repo")
    val destination = directoryUnder(tempParent(), "repo")
    ws.withoutDotProject("repo")
    ws.alreadyRegistered(open = false, location = destination)

    ws.importer(consent = false).import(destination, RepositorySource.CLONED_NOW) shouldBe
      CloneOutcome.ImportSkipped(
        destination,
        ImportSkipReason.NAME_TAKEN,
        RepositorySource.CLONED_NOW,
        projectName = "repo",
        leftoverProjectName = "repo",
      )

    verify(exactly = 0) { ws.project.create(any(), any()) }
    verify(exactly = 0) { ws.project.delete(any(), any(), any()) }
  }

  "a same-name project that is open, or registered elsewhere, is never touched" {
    val opened = WorkspaceFixture("repo")
    val openedDestination = directoryUnder(tempParent(), "repo")
    opened.withoutDotProject("repo")
    opened.alreadyRegistered(open = true, location = openedDestination)

    opened.importer().import(openedDestination, RepositorySource.CLONED_NOW) shouldBe
      CloneOutcome.ImportSkipped(
        openedDestination,
        ImportSkipReason.NAME_TAKEN,
        RepositorySource.CLONED_NOW,
        projectName = "repo",
      )
    verify(exactly = 0) { opened.project.delete(any(), any(), any()) }
    verify(exactly = 0) { opened.project.create(any(), any()) }

    val elsewhere = WorkspaceFixture("repo")
    val elsewhereDestination = directoryUnder(tempParent(), "repo")
    elsewhere.withoutDotProject("repo")
    elsewhere.alreadyRegistered(open = false, location = directoryUnder(tempParent(), "somewhere-else"))

    elsewhere.importer().import(elsewhereDestination, RepositorySource.CLONED_NOW) shouldBe
      CloneOutcome.ImportSkipped(
        elsewhereDestination,
        ImportSkipReason.NAME_TAKEN,
        RepositorySource.CLONED_NOW,
        projectName = "repo",
      )
    verify(exactly = 0) { elsewhere.project.delete(any(), any(), any()) }
    verify(exactly = 0) { elsewhere.project.create(any(), any()) }
  }

  "rejects a workspace-root folder whose name differs from the .project name, and reports that name" {
    val ws = WorkspaceFixture("actual-name")
    val destination = directoryUnder(ws.rootDir, "folder-name")
    writeDotProject(destination)
    ws.withDotProject()

    ws.importer().import(destination, RepositorySource.ADOPTED_EXISTING) shouldBe
      CloneOutcome.ImportSkipped(
        destination,
        ImportSkipReason.LOCATION_REJECTED,
        RepositorySource.ADOPTED_EXISTING,
        projectName = "actual-name",
      )

    verify(exactly = 0) { ws.project.create(any(), any()) }
  }

  "a failed create has nothing to compensate: no leftover and no delete" {
    val ws = WorkspaceFixture("repo")
    val destination = directoryUnder(tempParent(), "repo")
    ws.withoutDotProject("repo")
    every { ws.project.create(any(), any()) } throws IllegalStateException("create failed")

    ws.importer().import(destination, RepositorySource.CLONED_NOW) shouldBe
      CloneOutcome.ImportSkipped(
        destination,
        ImportSkipReason.IMPORT_FAILED,
        RepositorySource.CLONED_NOW,
        projectName = "repo",
      )

    verify(exactly = 0) { ws.project.delete(any(), any(), any()) }
  }

  "a failed open deregisters the half-made project, leaving no leftover" {
    val ws = WorkspaceFixture("repo")
    val destination = directoryUnder(tempParent(), "repo")
    ws.withoutDotProject("repo")
    every { ws.project.open(any()) } throws IllegalStateException("open failed")

    ws.importer().import(destination, RepositorySource.CLONED_NOW) shouldBe
      CloneOutcome.ImportSkipped(
        destination,
        ImportSkipReason.IMPORT_FAILED,
        RepositorySource.CLONED_NOW,
        projectName = "repo",
      )

    verify { ws.project.delete(false, true, null) }
    verify(exactly = 0) { ws.project.delete(true, any(), any()) }
  }

  // C14: the one path no real Eclipse can be made to take on purpose.
  "a failed open whose compensation also fails reports the lingering registration" {
    val ws = WorkspaceFixture("repo")
    val destination = directoryUnder(tempParent(), "repo")
    ws.withoutDotProject("repo")
    every { ws.project.open(any()) } throws IllegalStateException("open failed")
    every { ws.project.delete(false, true, null) } throws IllegalStateException("delete failed")

    ws.importer().import(destination, RepositorySource.CLONED_NOW) shouldBe
      CloneOutcome.ImportSkipped(
        destination,
        ImportSkipReason.IMPORT_FAILED,
        RepositorySource.CLONED_NOW,
        projectName = "repo",
        leftoverProjectName = "repo",
      )

    verify(exactly = 0) { ws.project.delete(true, any(), any()) }
  }

  "no workspace: NO_WORKSPACE named after the folder, because no description was read" {
    val destination = directoryUnder(tempParent(), "repo")
    val importer = ClonedProjectImporter(confirm = { true }, workspace = { null })

    importer.import(destination, RepositorySource.ADOPTED_EXISTING) shouldBe
      CloneOutcome.ImportSkipped(
        destination,
        ImportSkipReason.NO_WORKSPACE,
        RepositorySource.ADOPTED_EXISTING,
        projectName = "repo",
      )
  }

  "a workspace without a root location is NO_WORKSPACE too" {
    val ws = WorkspaceFixture("repo")
    val destination = directoryUnder(tempParent(), "repo")
    every { ws.root.location } returns null

    ws.importer().import(destination, RepositorySource.CLONED_NOW) shouldBe
      CloneOutcome.ImportSkipped(
        destination,
        ImportSkipReason.NO_WORKSPACE,
        RepositorySource.CLONED_NOW,
        projectName = "repo",
      )
  }

  // Implementing the design's numbered steps in their written order returns NAME_TAKEN here and
  // makes crash recovery unreachable; this pins the release as being evaluated first.
  "the orphan release is evaluated before concluding the name is taken" {
    val ws = WorkspaceFixture("repo")
    val destination = directoryUnder(tempParent(), "repo")
    ws.withoutDotProject("repo")
    ws.alreadyRegistered(open = false, location = destination)

    val outcome = ws.importer(consent = true).import(destination, RepositorySource.CLONED_NOW)

    (outcome is CloneOutcome.Imported) shouldBe true
    verifyOrder {
      ws.project.delete(false, true, null)
      ws.project.create(any(), any())
    }
  }
})
