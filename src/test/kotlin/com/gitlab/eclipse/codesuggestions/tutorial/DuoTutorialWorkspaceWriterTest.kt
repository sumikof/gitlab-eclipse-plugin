package com.gitlab.eclipse.codesuggestions.tutorial

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.core.resources.IResource
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.core.runtime.OperationCanceledException
import java.nio.file.Files

/**
 * §9.2 creation order and compensation, run headlessly against [TutorialWorkspaceFixture] (spec
 * `DuoTutorialWorkspaceWriterTest`). `runInWorkspace` is called directly: the job manager's rule
 * handling is the platform's, and the one behaviour that depends on it — a cancel before the body
 * runs — is covered in `DuoTutorialHandlerTest`.
 */
class DuoTutorialWorkspaceWriterTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val id = "0f3c1c7e-1111-4a4a-8b8b-000000000001"
  val expectedDirectory = STATE_DIRECTORY.resolve(id)

  fun run(writer: DuoTutorialWorkspaceWriter, monitor: IProgressMonitor = NullProgressMonitor()): IStatus =
    writer.runInWorkspace(monitor)

  /** Everything the compensation must have done: registration gone, the reserved directory gone. */
  fun TutorialWorkspaceFixture.shouldBeCompensated() {
    exists shouldBe false
    fileSystem.deleted shouldContainExactly listOf(expectedDirectory)
    fileSystem.existing.contains(expectedDirectory) shouldBe false
  }

  fun TutorialWorkspaceFixture.shouldBeUntouched() {
    calls.shouldBeEmpty()
    fileSystem.touched.shouldBeEmpty()
    backing.isEmpty() shouldBe true
  }

  describe("job shape") {
    it("locks the workspace root, is a user job and starts out Cancelled before it ever runs") {
      val fixture = TutorialWorkspaceFixture()
      val writer = fixture.writer()

      writer.rule shouldBe fixture.root
      writer.isUser shouldBe true
      // §9.2: a cancel while waiting for the root rule never calls runInWorkspace, so the initial
      // value is the outcome the handler reads then.
      writer.outcome shouldBe WriterOutcome.Cancelled
    }
  }

  describe("creating the tutorial from nothing") {
    it("reserves <state>/duo-tutorial/<UUID>, points the description there and never touches the default location") {
      val fixture = TutorialWorkspaceFixture()
      fixture.fileSystem.existing.add(DEFAULT_LOCATION) // a user folder of the same name, R9

      val writer = fixture.writer { id }
      val status = run(writer)

      status.isOK shouldBe true
      writer.outcome shouldBe WriterOutcome.Ready(fixture.file)
      fixture.fileSystem.created shouldContainExactly listOf(expectedDirectory)
      fixture.descriptionLocation shouldBe expectedDirectory.toUri()
      fixture.fileSystem.touched.none { it.startsWith(DEFAULT_LOCATION) } shouldBe true
      fixture.fileSystem.existing.contains(DEFAULT_LOCATION) shouldBe true
      fixture.fileSystem.deleted.shouldBeEmpty()
      fixture.calls shouldContainExactly listOf("create", "save", "open", "property", "file.create")
    }

    it("records the id and the location read after create, then writes the property after open") {
      val fixture = TutorialWorkspaceFixture()
      val writer = fixture.writer { id }

      run(writer)

      writer.outcome shouldBe WriterOutcome.Ready(fixture.file)
      // The unresolved handle answers null until create has run (fixture): recording after it is
      // the only way the location can be non-empty.
      fixture.backing[PreferenceConstants.DUO_TUTORIAL_PROJECT_ID] shouldBe id
      fixture.backing[PreferenceConstants.DUO_TUTORIAL_PROJECT_LOCATION] shouldBe expectedDirectory.toUri().toString()
      fixture.calls shouldContainExactly listOf("create", "save", "open", "property", "file.create")
      fixture.property shouldBe id
      fixture.fileContents shouldContainExactly listOf(DuoTutorialContent.TEXT)
    }

    it("leaves a record a new store instance reads back as owned (restart, Review Focus 1)") {
      val fixture = TutorialWorkspaceFixture()
      run(fixture.writer { id })

      val reloaded = DuoTutorialOwnership(fakeStore(fixture.backing))

      reloaded.isOwned(fixture.project) shouldBe true
      // And a second run from the reloaded record only opens (§9.2 "一致・ファイルあり").
      val second = DuoTutorialWorkspaceWriter(
        fixture.workspace,
        reloaded,
        { STATE_DIRECTORY },
        fixture.fileSystem,
      ) { "id-2" }
      fixture.calls.clear()
      run(second)
      second.outcome shouldBe WriterOutcome.Ready(fixture.file)
      fixture.calls.shouldBeEmpty()
    }

    it("fails without creating anything when the reserved directory already exists") {
      val fixture = TutorialWorkspaceFixture()
      fixture.fileSystem.existing.add(expectedDirectory)
      val writer = fixture.writer { id }

      val status = run(writer)

      writer.outcome shouldBe WriterOutcome.Failed
      status.isOK shouldBe true
      fixture.calls.shouldBeEmpty()
      fixture.fileSystem.deleted.shouldBeEmpty()
      fixture.backing.isEmpty() shouldBe true
    }
  }

  describe("compensation (§9.2): reserved, property not yet set") {
    it("an exception inside create after registration removes the registration and the directory: Failed") {
      val fixture = TutorialWorkspaceFixture()
      fixture.onCreate = { throw TutorialWorkspaceFixture.coreException() }
      val writer = fixture.writer { id }

      val status = run(writer)

      writer.outcome shouldBe WriterOutcome.Failed
      status.isOK shouldBe true
      fixture.shouldBeCompensated()
      val (flags, monitor) = fixture.deleteCalls.single()
      (flags and IResource.NEVER_DELETE_PROJECT_CONTENT) shouldBe IResource.NEVER_DELETE_PROJECT_CONTENT
      monitor.shouldBeInstanceOf<NullProgressMonitor>()
      fixture.calls shouldContainExactly listOf("create", "delete")
    }

    it("a cancellation inside create compensates the same way: Cancelled") {
      val fixture = TutorialWorkspaceFixture()
      fixture.onCreate = { throw OperationCanceledException() }
      val writer = fixture.writer { id }

      val status = run(writer)

      writer.outcome shouldBe WriterOutcome.Cancelled
      status.severity shouldBe IStatus.CANCEL
      fixture.shouldBeCompensated()
    }

    it("a save failure restores the previous record, compensates, and neither opens nor sets the property") {
      val fixture = TutorialWorkspaceFixture()
      fixture.backing[PreferenceConstants.DUO_TUTORIAL_PROJECT_ID] = "old-id"
      fixture.backing[PreferenceConstants.DUO_TUTORIAL_PROJECT_LOCATION] = "file:///old/"
      fixture.onSave = { throw java.io.IOException("disk full") }
      val writer = fixture.writer { id }

      run(writer)

      writer.outcome shouldBe WriterOutcome.Failed
      fixture.backing[PreferenceConstants.DUO_TUTORIAL_PROJECT_ID] shouldBe "old-id"
      fixture.backing[PreferenceConstants.DUO_TUTORIAL_PROJECT_LOCATION] shouldBe "file:///old/"
      fixture.shouldBeCompensated()
      fixture.calls shouldContainExactly listOf("create", "delete")
    }

    it("an open failure compensates: Failed") {
      val fixture = TutorialWorkspaceFixture()
      fixture.onOpen = { throw TutorialWorkspaceFixture.coreException() }
      val writer = fixture.writer { id }

      run(writer)

      writer.outcome shouldBe WriterOutcome.Failed
      fixture.shouldBeCompensated()
      fixture.property shouldBe null
    }

    it("a property write failure compensates: Failed") {
      val fixture = TutorialWorkspaceFixture()
      fixture.onSetProperty = { throw TutorialWorkspaceFixture.coreException() }
      val writer = fixture.writer { id }

      run(writer)

      writer.outcome shouldBe WriterOutcome.Failed
      fixture.shouldBeCompensated()
      fixture.calls shouldContainExactly listOf("create", "save", "open", "property", "delete")
    }

    it("reports ERROR to the platform only when the compensation itself fails") {
      val fixture = TutorialWorkspaceFixture()
      fixture.onOpen = { throw TutorialWorkspaceFixture.coreException() }
      fixture.fileSystem.failDelete = true
      val writer = fixture.writer { id }

      val status = run(writer)

      writer.outcome shouldBe WriterOutcome.Failed
      status.severity shouldBe IStatus.ERROR
      fixture.exists shouldBe false
      fixture.fileSystem.deleted.shouldBeEmpty()
    }

    it("completes the compensation with a NullProgressMonitor even though the job's monitor is cancelled") {
      val fixture = TutorialWorkspaceFixture()
      val monitor = NullProgressMonitor()
      fixture.onOpen = { monitor.isCanceled = true }
      val writer = fixture.writer { id }

      val status = run(writer, monitor)

      writer.outcome shouldBe WriterOutcome.Cancelled
      status.severity shouldBe IStatus.CANCEL
      // The fixture's delete throws OperationCanceledException for a cancelled monitor; it did not.
      fixture.shouldBeCompensated()
      fixture.deleteCalls.single().second.shouldBeInstanceOf<NullProgressMonitor>()
    }
  }

  describe("after the property is set the project is owned and stays") {
    it("a CreateFile failure leaves the project: Failed, nothing deleted") {
      val fixture = TutorialWorkspaceFixture()
      fixture.onFileCreate = {
        fixture.fileExists = false
        throw TutorialWorkspaceFixture.coreException()
      }
      val writer = fixture.writer { id }

      run(writer)

      writer.outcome shouldBe WriterOutcome.Failed
      fixture.exists shouldBe true
      fixture.property shouldBe id
      fixture.deleteCalls.shouldBeEmpty()
      fixture.fileSystem.deleted.shouldBeEmpty()
      fixture.calls shouldContainExactly listOf("create", "save", "open", "property", "file.create", "refresh")
    }

    it("a CreateFile CoreException on a file already on disk refreshes and falls to the 'exists' row: no overwrite") {
      val fixture = TutorialWorkspaceFixture()
      fixture.ownedProject(id, fileExists = false)
      fixture.onFileCreate = {
        fixture.fileExists = false
        throw TutorialWorkspaceFixture.coreException()
      }
      fixture.onRefresh = { fixture.fileExists = true }
      val writer = fixture.writer { "unused" }

      run(writer)

      writer.outcome shouldBe WriterOutcome.Ready(fixture.file)
      fixture.calls shouldContainExactly listOf("file.create", "refresh")
      fixture.fileSystem.touched.shouldBeEmpty()
    }
  }

  describe("state is re-read inside the job") {
    it("refuses a closed same-named project and changes nothing") {
      val fixture = TutorialWorkspaceFixture()
      fixture.userProject(open = false)
      val writer = fixture.writer()

      run(writer)

      writer.outcome shouldBe WriterOutcome.Refused(RefuseReason.PROJECT_CLOSED)
      fixture.shouldBeUntouched()
      fixture.open shouldBe false
    }

    it("refuses an open same-named project that is not owned and adds no file") {
      val fixture = TutorialWorkspaceFixture()
      fixture.userProject(open = true)
      val writer = fixture.writer()

      run(writer)

      writer.outcome shouldBe WriterOutcome.Refused(RefuseReason.NOT_OWNED)
      fixture.shouldBeUntouched()
      fixture.fileExists shouldBe false
    }

    it("only opens when owned and the file exists: no writes at all") {
      val fixture = TutorialWorkspaceFixture()
      fixture.ownedProject(id, fileExists = true)
      val writer = fixture.writer()

      run(writer)

      writer.outcome shouldBe WriterOutcome.Ready(fixture.file)
      fixture.calls.shouldBeEmpty()
      fixture.fileSystem.touched.shouldBeEmpty()
    }

    it("creates only the file when owned and the file is missing") {
      val fixture = TutorialWorkspaceFixture()
      fixture.ownedProject(id, fileExists = false)
      val writer = fixture.writer()

      run(writer)

      writer.outcome shouldBe WriterOutcome.Ready(fixture.file)
      fixture.calls shouldContainExactly listOf("file.create")
      fixture.fileSystem.touched.shouldBeEmpty()
    }
  }

  describe("cancellation between steps") {
    it("before the reservation: nothing is created and nothing is deleted") {
      val fixture = TutorialWorkspaceFixture()
      val monitor = NullProgressMonitor().also { it.isCanceled = true }
      val writer = fixture.writer { id }

      val status = run(writer, monitor)

      writer.outcome shouldBe WriterOutcome.Cancelled
      status.severity shouldBe IStatus.CANCEL
      fixture.shouldBeUntouched()
    }

    it("after the reservation, before create: the directory is deleted and create never runs (Review Focus 4)") {
      val fixture = TutorialWorkspaceFixture()
      val monitor = NullProgressMonitor()
      fixture.fileSystem.onCreateDirectory = { monitor.isCanceled = true }
      val writer = fixture.writer { id }

      run(writer, monitor)

      writer.outcome shouldBe WriterOutcome.Cancelled
      fixture.calls.shouldBeEmpty()
      fixture.fileSystem.deleted shouldContainExactly listOf(expectedDirectory)
      fixture.fileSystem.existing.contains(expectedDirectory) shouldBe false
    }

    it("after create: registration and directory are removed") {
      val fixture = TutorialWorkspaceFixture()
      val monitor = NullProgressMonitor()
      fixture.onCreate = { monitor.isCanceled = true }
      val writer = fixture.writer { id }

      run(writer, monitor)

      writer.outcome shouldBe WriterOutcome.Cancelled
      fixture.shouldBeCompensated()
      fixture.backing.isEmpty() shouldBe true
    }

    it("after the save: compensated, open never runs") {
      val fixture = TutorialWorkspaceFixture()
      val monitor = NullProgressMonitor()
      fixture.onSave = {
        fixture.calls += "save"
        monitor.isCanceled = true
      }
      val writer = fixture.writer { id }

      run(writer, monitor)

      writer.outcome shouldBe WriterOutcome.Cancelled
      fixture.shouldBeCompensated()
      fixture.calls.contains("open") shouldBe false
    }

    it("after open, before the property: compensated") {
      val fixture = TutorialWorkspaceFixture()
      val monitor = NullProgressMonitor()
      fixture.onOpen = { monitor.isCanceled = true }
      val writer = fixture.writer { id }

      run(writer, monitor)

      writer.outcome shouldBe WriterOutcome.Cancelled
      fixture.shouldBeCompensated()
      fixture.calls.contains("property") shouldBe false
    }

    it("after the property: the owned project stays and no file is created") {
      val fixture = TutorialWorkspaceFixture()
      val monitor = NullProgressMonitor()
      fixture.onSetProperty = { monitor.isCanceled = true }
      val writer = fixture.writer { id }

      run(writer, monitor)

      writer.outcome shouldBe WriterOutcome.Cancelled
      fixture.exists shouldBe true
      fixture.property shouldBe id
      fixture.deleteCalls.shouldBeEmpty()
      fixture.fileSystem.deleted.shouldBeEmpty()
      fixture.calls.contains("file.create") shouldBe false
    }
  }

  describe("logging (A16)") {
    it("names the steps and exception classes, never the location or the file body") {
      val logged = captureLog()
      val fixture = TutorialWorkspaceFixture()
      fixture.onOpen = { throw TutorialWorkspaceFixture.coreException() }

      run(fixture.writer { id })

      logged.any { it.contains("CreateProject") } shouldBe true
      logged.any { it.contains(org.eclipse.core.runtime.CoreException::class.java.name) } shouldBe true
      logged.forEach {
        it shouldNotContain STATE_DIRECTORY.toString()
        it shouldNotContain id
        it shouldNotContain "const multiply"
      }
    }
  }

  describe("DuoTutorialFileSystem.Default") {
    it("creates a fresh directory, refuses an existing one, and deletes recursively") {
      val base = Files.createTempDirectory("duo-tutorial-fs")
      val parent = base.resolve("duo-tutorial")
      val target = parent.resolve("abc")

      DuoTutorialFileSystem.Default.createDirectories(parent)
      DuoTutorialFileSystem.Default.createDirectory(target)
      Files.writeString(target.resolve("nested.txt"), "x")
      Files.createDirectory(target.resolve("sub"))
      Files.writeString(target.resolve("sub").resolve("deep.txt"), "y")

      runCatching { DuoTutorialFileSystem.Default.createDirectory(target) }.isFailure shouldBe true

      DuoTutorialFileSystem.Default.deleteRecursively(target)
      Files.exists(target) shouldBe false
      Files.exists(parent) shouldBe true

      // Missing already: a no-op, not a failure.
      DuoTutorialFileSystem.Default.deleteRecursively(target)
      DuoTutorialFileSystem.Default.deleteRecursively(base)
      Files.exists(base) shouldBe false
    }
  }
})
