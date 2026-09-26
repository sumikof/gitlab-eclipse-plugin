package com.gitlab.eclipse.codesuggestions.tutorial

import com.gitlab.eclipse.preferences.PreferenceConstants
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import org.eclipse.core.internal.resources.Workspace
import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.IProject
import org.eclipse.core.resources.IProjectDescription
import org.eclipse.core.resources.IWorkspace
import org.eclipse.core.resources.IWorkspaceRoot
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.runtime.CoreException
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.OperationCanceledException
import org.eclipse.core.runtime.Platform
import org.eclipse.core.runtime.QualifiedName
import org.eclipse.core.runtime.Status
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.osgi.framework.Bundle
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList

/** The plugin's state directory for tutorials, as a pure path: nothing here touches the disk. */
internal val STATE_DIRECTORY: Path = Paths.get("/state", "duo-tutorial")

/** The workspace root on disk (again pure): the default location `<workspace>/GitLab Duo Tutorial` sits under it. */
internal val WORKSPACE_DIRECTORY: Path = Paths.get("/ws")
internal val DEFAULT_LOCATION: Path = WORKSPACE_DIRECTORY.resolve(DuoTutorialContent.PROJECT_NAME)

internal val PROPERTY_ID = QualifiedName("com.gitlab.eclipse", "duoTutorialId")

/**
 * A file system that records every path any operation was given, so a spec can prove a location
 * (the default one, R9) was never read or written. [existing] seeds what already exists.
 */
internal class RecordingFileSystem : DuoTutorialFileSystem {
  val touched = CopyOnWriteArrayList<Path>()
  val created = CopyOnWriteArrayList<Path>()
  val deleted = CopyOnWriteArrayList<Path>()
  val existing = mutableSetOf<Path>()
  var onCreateDirectory: (Path) -> Unit = {}
  var failDelete = false

  override fun createDirectories(path: Path) {
    touched.add(path)
    existing.add(path)
  }

  override fun createDirectory(path: Path) {
    touched.add(path)
    if (path in existing) throw FileAlreadyExistsException(path.toString())
    existing.add(path)
    created.add(path)
    onCreateDirectory(path)
  }

  override fun deleteRecursively(path: Path) {
    touched.add(path)
    if (failDelete) throw IOException("busy")
    existing.remove(path)
    deleted.add(path)
  }
}

/**
 * A preference store over a shared [backing] map. Two instances over the same map model "a new
 * store instance after a restart reads what the previous one saved" (Review Focus 1).
 */
internal fun fakeStore(backing: MutableMap<String, String>, onSave: () -> Unit = {}): ScopedPreferenceStore {
  val store = mockk<ScopedPreferenceStore>(relaxed = true)
  every { store.getString(any()) } answers { backing[firstArg()].orEmpty() }
  val key = slot<String>()
  val value = slot<String>()
  every { store.setValue(capture(key), capture(value)) } answers { backing[key.captured] = value.captured }
  every { store.save() } answers { onSave() }
  return store
}

/**
 * The workspace as the writer and the opener see it: one `GitLab Duo Tutorial` project handle
 * whose registration, open state, persistent property, location and file are plain mutable fields,
 * plus hooks that run *after* each side effect so a spec can inject a failure or a cancellation at
 * exactly that point (§23: "create の途中", "保存後", "open 後", ...).
 *
 * The mocks are strict except for the description: any call the flow is not supposed to make —
 * `root.location`, `workspace.run`, `project.delete` while nothing was created — throws.
 */
internal class TutorialWorkspaceFixture {
  val backing = mutableMapOf<String, String>()
  var onSave: () -> Unit = {}
  val store: ScopedPreferenceStore = fakeStore(backing) { onSave() }
  val ownership = DuoTutorialOwnership(store)
  val fileSystem = RecordingFileSystem()

  @Volatile var exists = false

  @Volatile var open = false

  @Volatile var property: String? = null

  @Volatile var location: URI? = null

  @Volatile var fileExists = false

  /** Side-effect hooks; each runs after the fake has applied the effect. */
  var onCreate: (IProgressMonitor) -> Unit = {}
  var onOpen: (IProgressMonitor) -> Unit = {}
  var onSetProperty: () -> Unit = {}
  var onFileCreate: (IProgressMonitor) -> Unit = {}
  var onRefresh: () -> Unit = {}

  /** Ordered record of the side effects: `create`, `save`, `open`, `property`, `file.create`, `refresh`, `delete`. */
  val calls = CopyOnWriteArrayList<String>()
  val deleteCalls = CopyOnWriteArrayList<Pair<Int, IProgressMonitor?>>()
  val fileContents = CopyOnWriteArrayList<String>()
  var descriptionLocation: URI? = null

  val description: IProjectDescription = mockk(relaxed = true)
  val project: IProject = mockk()
  val file: IFile = mockk()
  val root: IWorkspaceRoot = mockk()

  /**
   * Typed as the internal [Workspace] because `WorkspaceJob`'s constructor casts
   * `ResourcesPlugin.getWorkspace()` to it; the static lookup is redirected to this mock (same
   * `mockkStatic(ResourcesPlugin)` pattern as `WorkspaceFileOpenerTest`) so the job can be built
   * headlessly. `LoggingKotestExtension.afterSpec` unmocks it again.
   */
  val workspace: IWorkspace = mockk<Workspace>()

  init {
    mockkStatic(ResourcesPlugin::class)
    every { ResourcesPlugin.getWorkspace() } returns workspace
    every { root.contains(any()) } answers { firstArg<Any?>() === root }
    every { root.isConflicting(any()) } answers { firstArg<Any?>() === root }
    every { workspace.root } returns root
    every { workspace.newProjectDescription(DuoTutorialContent.PROJECT_NAME) } returns description
    val uri = slot<URI>()
    every { description.setLocationURI(capture(uri)) } answers { descriptionLocation = uri.captured }
    every { root.getProject(DuoTutorialContent.PROJECT_NAME) } returns project

    every { project.name } returns DuoTutorialContent.PROJECT_NAME
    every { project.workspace } returns workspace
    every { project.exists() } answers { exists }
    every { project.isOpen } answers { open }
    every { project.locationURI } answers { location }
    every { project.getPersistentProperty(PROPERTY_ID) } answers { property }
    every { project.getFile(DuoTutorialContent.FILE_NAME) } returns file
    every { project.create(any<IProjectDescription>(), any()) } answers {
      calls += "create"
      exists = true
      location = descriptionLocation
      onCreate(secondArg())
    }
    every { project.open(any()) } answers {
      calls += "open"
      open = true
      onOpen(firstArg())
    }
    every { project.setPersistentProperty(PROPERTY_ID, any()) } answers {
      calls += "property"
      property = secondArg()
      onSetProperty()
    }
    every { project.delete(any<Int>(), any()) } answers {
      val monitor = secondArg<IProgressMonitor?>()
      calls += "delete"
      deleteCalls += firstArg<Int>() to monitor
      // What a real cancelled monitor does to a workspace operation (§23: the compensation must
      // not hand the job's cancelled monitor to the delete).
      if (monitor?.isCanceled == true) throw OperationCanceledException()
      exists = false
      open = false
      property = null
    }

    every { file.project } returns project
    every { file.workspace } returns workspace
    every { file.name } returns DuoTutorialContent.FILE_NAME
    every { file.exists() } answers { fileExists }
    every { file.create(any<InputStream>(), any<Boolean>(), any()) } answers {
      calls += "file.create"
      fileContents += firstArg<InputStream>().readBytes().decodeToString()
      fileExists = true
      onFileCreate(thirdArg())
    }
    every { file.refreshLocal(any(), any()) } answers {
      calls += "refresh"
      onRefresh()
    }
    onSave = { calls += "save" }
  }

  /** The ownership record as [DuoTutorialWorkspaceWriter] would have left it for [id]. */
  fun ownedProject(id: String, fileExists: Boolean) {
    exists = true
    open = true
    property = id
    location = STATE_DIRECTORY.resolve(id).toUri()
    backing[PreferenceConstants.DUO_TUTORIAL_PROJECT_ID] = id
    backing[PreferenceConstants.DUO_TUTORIAL_PROJECT_LOCATION] = location.toString()
    this.fileExists = fileExists
  }

  /** A same-named project the user made (or re-imported): registered, no property, no record. */
  fun userProject(open: Boolean) {
    exists = true
    this.open = open
    property = null
    location = DEFAULT_LOCATION.toUri()
  }

  fun writer(newId: () -> String = { "id-1" }): DuoTutorialWorkspaceWriter =
    DuoTutorialWorkspaceWriter(workspace, ownership, { STATE_DIRECTORY }, fileSystem, newId)

  companion object {
    fun coreException(): CoreException = CoreException(Status.error("workspace refused"))
  }
}

/**
 * Installs a recording log into the static `Platform` mock [LoggingKotestExtension] set up, and
 * returns the list every message lands in (same shape as `WorkspaceFileOpenerTest`).
 */
internal fun captureLog(): List<String> {
  val recorded = CopyOnWriteArrayList<String>()
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
