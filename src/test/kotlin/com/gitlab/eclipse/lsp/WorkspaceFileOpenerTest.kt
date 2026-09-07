package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.messages.OpenFileParams
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.eclipse.core.filesystem.EFS
import org.eclipse.core.filesystem.IFileStore
import org.eclipse.core.resources.IContainer
import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.IWorkspace
import org.eclipse.core.resources.IWorkspaceRoot
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IPath
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Platform
import org.eclipse.swt.SWTException
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.ide.IDE
import org.osgi.framework.Bundle
import java.io.File
import java.net.URI

/**
 * A path segment that appears in every path this spec uses, so "no log line names the file" can be
 * asserted by looking for one distinctive string.
 */
private const val SECRET = "SECRETDIR"

private const val ROOT_A = "/tmp/$SECRET/rootA"
private const val ROOT_B = "/tmp/$SECRET/rootB"
private const val ROOT_C = "/tmp/$SECRET/rootC"

/** The `file:` URI the opener is expected to hand on for [path]. */
private fun uriOf(path: String): URI = File(path).toURI()

/**
 * Installs a log that records every string handed to it, and returns that recording.
 *
 * Must be called **before** the opener is constructed: `WorkspaceFileOpener` takes its log in its
 * initialiser, so an opener built earlier holds the extension's discard-everything mock instead.
 */
private fun captureLog(): List<String> {
  val recorded = mutableListOf<String>()
  val log = mockk<ILog>()
  val message = slot<String>()
  val status = slot<IStatus>()
  every { log.error(capture(message)) } answers { recorded += message.captured }
  every { log.error(capture(message), any()) } answers { recorded += message.captured }
  every { log.warn(capture(message)) } answers { recorded += message.captured }
  every { log.warn(capture(message), any()) } answers { recorded += message.captured }
  every { log.info(capture(message)) } answers { recorded += message.captured }
  every { log.info(capture(message), any()) } answers { recorded += message.captured }
  every { log.log(capture(status)) } answers { recorded += status.captured.message }
  every { Platform.getLog(any<Bundle>()) } returns log
  every { Platform.getLog(any<Class<*>>()) } returns log
  return recorded
}

class WorkspaceFileOpenerTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  /** Every URI the opener asked to be opened, in order. */
  val opened = mutableListOf<URI>()

  beforeTest { opened.clear() }

  /**
   * An opener over a made-up filesystem. [roots] is the project order, [existing] the set of
   * paths that exist (as OS strings), and the UI hop runs inline so a test can assert on what
   * happened without a display.
   */
  fun opener(
    roots: List<String> = emptyList(),
    existing: Set<String> = emptySet(),
    onUiThread: (Runnable) -> Unit = { it.run() },
    openInEditor: (URI) -> Unit = { opened += it },
  ) = WorkspaceFileOpener(
    onUiThread = onUiThread,
    workspaceRoots = { roots.map { IPath.fromOSString(it) } },
    fileExists = { it.toOSString() in existing },
    openInEditor = openInEditor,
  )

  describe("open, path resolution") {
    it("uses an absolute path exactly as given, never joined to a root") {
      val absolute = "/tmp/$SECRET/outside/a.txt"
      // The root-joined spelling exists too: were the absolute branch gone, the joined one would
      // be found and this assertion would name the wrong file rather than passing anyway.
      val joined = IPath.fromOSString(ROOT_A).append(absolute).toOSString()
      opener(roots = listOf(ROOT_A), existing = setOf(absolute, joined))
        .open(OpenFileParams(absolute))

      opened.shouldContainExactly(uriOf(absolute))
    }

    it("resolves a relative path against the roots in order and takes the first that exists") {
      // Exists under the SECOND root only: pins "first that exists", not "first root".
      val underB = "$ROOT_B/src/a.txt"
      opener(roots = listOf(ROOT_A, ROOT_B, ROOT_C), existing = setOf(underB))
        .open(OpenFileParams("src/a.txt"))

      opened.shouldContainExactly(uriOf(underB))
    }

    it("takes the first root when the relative path exists under more than one") {
      val underA = "$ROOT_A/src/a.txt"
      val underB = "$ROOT_B/src/a.txt"
      opener(roots = listOf(ROOT_A, ROOT_B, ROOT_C), existing = setOf(underA, underB))
        .open(OpenFileParams("src/a.txt"))

      opened.shouldContainExactly(uriOf(underA))
    }

    it("opens nothing when the relative path exists under no root") {
      opener(roots = listOf(ROOT_A, ROOT_B), existing = setOf("$ROOT_C/src/a.txt"))
        .open(OpenFileParams("src/a.txt"))

      opened.shouldBeEmpty()
    }

    it("opens nothing when there is no workspace root at all") {
      shouldNotThrowAny { opener(roots = emptyList()).open(OpenFileParams("src/a.txt")) }

      opened.shouldBeEmpty()
    }

    it("opens nothing when the absolute path does not exist") {
      opener(roots = listOf(ROOT_A), existing = emptySet())
        .open(OpenFileParams("/tmp/$SECRET/outside/a.txt"))

      opened.shouldBeEmpty()
    }
  }

  describe("open, unusable payloads") {
    it("opens nothing when filePath is null") {
      opener(roots = listOf(ROOT_A), existing = setOf(ROOT_A)).open(OpenFileParams(null))

      opened.shouldBeEmpty()
    }

    // The empty and blank cases carry their own liveness: the root itself, and the root joined to
    // the blank string, are made to exist, so dropping the payload guard opens something.
    it("opens nothing when filePath is empty") {
      opener(roots = listOf(ROOT_A), existing = setOf(ROOT_A)).open(OpenFileParams(""))

      opened.shouldBeEmpty()
    }

    it("opens nothing when filePath is only whitespace") {
      val blank = "   "
      val joined = IPath.fromOSString(ROOT_A).append(blank).toOSString()
      opener(roots = listOf(ROOT_A), existing = setOf(ROOT_A, joined)).open(OpenFileParams(blank))

      opened.shouldBeEmpty()
    }
  }

  describe("open, containment") {
    it("contains an open that throws") {
      val target = "$ROOT_A/src/a.txt"
      val subject = opener(
        roots = listOf(ROOT_A),
        existing = setOf(target),
        openInEditor = { error("no editor for this file") },
      )

      shouldNotThrowAny { subject.open(OpenFileParams("src/a.txt")) }
    }

    it("contains a UI-thread hop that throws") {
      val target = "$ROOT_A/src/a.txt"
      val subject = opener(
        roots = listOf(ROOT_A),
        existing = setOf(target),
        onUiThread = { throw SWTException("display disposed") },
      )

      shouldNotThrowAny { subject.open(OpenFileParams("src/a.txt")) }
      opened.shouldBeEmpty()
    }

    it("opens on the UI thread, not on the calling thread") {
      val target = "$ROOT_A/src/a.txt"
      val deferred = mutableListOf<Runnable>()
      opener(roots = listOf(ROOT_A), existing = setOf(target), onUiThread = { deferred += it })
        .open(OpenFileParams("src/a.txt"))

      // Nothing opened yet: the open is inside the runnable handed to the display, never run here.
      opened.shouldBeEmpty()
      deferred.size shouldBe 1
      deferred.single().run()
      opened.shouldContainExactly(uriOf(target))
    }
  }

  describe("open, log hygiene") {
    it("names neither the path nor the URI when it opens a file") {
      val logged = captureLog()
      val target = "$ROOT_A/src/a.txt"
      opener(roots = listOf(ROOT_A), existing = setOf(target)).open(OpenFileParams("src/a.txt"))

      opened.shouldContainExactly(uriOf(target))
      logged.forEach { it.contains(SECRET) shouldBe false }
    }

    it("names no path when nothing exists at the requested path") {
      val logged = captureLog()
      opener(roots = listOf(ROOT_A), existing = emptySet()).open(OpenFileParams("src/$SECRET.txt"))

      logged.forEach { it.contains(SECRET) shouldBe false }
    }

    it("logs an open failure by exception type only") {
      val logged = captureLog()
      val target = "$ROOT_A/src/a.txt"
      opener(
        roots = listOf(ROOT_A),
        existing = setOf(target),
        openInEditor = { throw IllegalStateException("no editor for $target") },
      ).open(OpenFileParams("src/a.txt"))

      logged.any { it.contains(IllegalStateException::class.java.name) } shouldBe true
      logged.forEach { it.contains(SECRET) shouldBe false }
    }

    it("logs a failed UI-thread hop by exception type only") {
      val logged = captureLog()
      val target = "$ROOT_A/src/a.txt"
      opener(
        roots = listOf(ROOT_A),
        existing = setOf(target),
        onUiThread = { throw SWTException("display disposed: $target") },
      ).open(OpenFileParams("src/a.txt"))

      logged.any { it.contains(SWTException::class.java.name) } shouldBe true
      logged.forEach { it.contains(SECRET) shouldBe false }
    }
  }

  // The production default for `openInEditor`. This is where `openFile` and `applyEdit` have to
  // agree, so the lookup itself — not just its result — is asserted.
  describe("openInActivePage") {
    val target = uriOf("/tmp/$SECRET/rootA/src/a.txt")
    val page = mockk<IWorkbenchPage>()
    val root = mockk<IWorkspaceRoot>()
    val store = mockk<IFileStore>()

    beforeTest {
      mockkStatic(PlatformUI::class, ResourcesPlugin::class, EFS::class, IDE::class)
      val window = mockk<IWorkbenchWindow>()
      val workbench = mockk<IWorkbench>()
      val workspace = mockk<IWorkspace>()
      every { window.activePage } returns page
      every { workbench.activeWorkbenchWindow } returns window
      every { workspace.root } returns root
      every { PlatformUI.getWorkbench() } returns workbench
      every { ResourcesPlugin.getWorkspace() } returns workspace
      every { EFS.getStore(any()) } returns store
      every { IDE.openEditor(any(), any<IFile>()) } returns mockk()
      every { IDE.openEditorOnFileStore(any(), any()) } returns mockk()
    }

    afterTest { unmockkStatic(PlatformUI::class, ResourcesPlugin::class, EFS::class, IDE::class) }

    it("opens a workspace file as a workspace resource, hidden and team-private included") {
      val file = mockk<IFile>()
      every { root.findFilesForLocationURI(any(), any()) } returns arrayOf(file)

      openInActivePage(target)

      // The flags are the assertion: without them a file under a hidden linked folder looks like it
      // is outside the workspace here while `applyEdit` still sees it inside — two buffers.
      verify(exactly = 1) {
        root.findFilesForLocationURI(
          target,
          IContainer.INCLUDE_HIDDEN or IContainer.INCLUDE_TEAM_PRIVATE_MEMBERS,
        )
      }
      verify(exactly = 1) { IDE.openEditor(page, file) }
      verify(exactly = 0) { IDE.openEditorOnFileStore(any(), any()) }
    }

    it("opens a file outside the workspace through its file store") {
      every { root.findFilesForLocationURI(any(), any()) } returns emptyArray()

      openInActivePage(target)

      verify(exactly = 1) { IDE.openEditorOnFileStore(page, store) }
      verify(exactly = 0) { IDE.openEditor(any(), any<IFile>()) }
    }

    it("opens nothing when there is no active page") {
      every { PlatformUI.getWorkbench().activeWorkbenchWindow } returns null
      every { root.findFilesForLocationURI(any(), any()) } returns emptyArray()

      openInActivePage(target)

      verify(exactly = 0) { IDE.openEditorOnFileStore(any(), any()) }
      verify(exactly = 0) { IDE.openEditor(any(), any<IFile>()) }
    }
  }
})
