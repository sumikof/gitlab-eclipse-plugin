package com.gitlab.eclipse.lsp.listeners

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams
import com.gitlab.eclipse.lsp.utils.workspaceFolders
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.IProject
import org.eclipse.core.resources.IResource
import org.eclipse.core.resources.IResourceChangeEvent
import org.eclipse.core.resources.IResourceDelta
import org.eclipse.core.resources.IResourceDeltaVisitor
import org.eclipse.core.resources.IWorkspace
import org.eclipse.core.resources.IWorkspaceRoot
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.WorkspaceFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectOpenLanguageServerListenerTest : DescribeSpec({
  val languageServer = mockk<GitLabLanguageServer>(relaxUnitFun = true)
  val wrapper = mockk<GitLabLanguageServerWrapper>()
  val folders = listOf(WorkspaceFolder("file:///ws/p1", "p1"))

  extensions(LoggingKotestExtension)

  beforeSpec {
    mockkStatic(ResourcesPlugin::getWorkspace)
    // `workspaceFolders` is a top-level val that calls ResourcesPlugin.getWorkspace();
    // it must be mocked or every test throws in a plain-JVM run.
    mockkStatic("com.gitlab.eclipse.lsp.utils.ProjectsWorkspaceFolderKt")
  }

  beforeEach {
    every { ResourcesPlugin.getWorkspace() } returns mockk<IWorkspace>(relaxUnitFun = true)
    every { workspaceFolders } returns folders
    every { wrapper.languageServer } returns languageServer
  }

  afterEach { clearAllMocks() }
  // LoggingKotestExtension.afterSpec runs unmockkAll(), so no extra teardown is needed.

  fun delta(
    resource: IResource,
    kind: Int = IResourceDelta.CHANGED,
    flags: Int = 0,
    children: List<IResourceDelta> = emptyList()
  ): IResourceDelta {
    val delta = mockk<IResourceDelta>()
    every { delta.resource } returns resource
    every { delta.kind } returns kind
    every { delta.flags } returns flags
    every { delta.affectedChildren } returns children.toTypedArray()
    return delta
  }

  // Mirrors the platform's traversal contract: visit(node); descend into children only if it
  // returned true. The production code only ever calls accept(visitor) on the root delta.
  fun eventFor(vararg rootChildren: IResourceDelta): IResourceChangeEvent {
    val root = delta(mockk<IWorkspaceRoot>(), children = rootChildren.toList())
    every { root.accept(any<IResourceDeltaVisitor>()) } answers {
      val visitor = firstArg<IResourceDeltaVisitor>()
      fun walk(node: IResourceDelta) {
        if (visitor.visit(node)) {
          node.affectedChildren.forEach { child -> walk(child) }
        }
      }
      walk(root)
    }
    val event = mockk<IResourceChangeEvent>()
    every { event.type } returns IResourceChangeEvent.POST_CHANGE
    every { event.delta } returns root
    return event
  }

  fun fireAndSettle(event: IResourceChangeEvent): TestScope {
    val scope = TestScope(StandardTestDispatcher())
    val listener = ProjectOpenLanguageServerListener(wrapper, scope, Mutex())
    listener.resourceChanged(event)
    scope.testScheduler.advanceUntilIdle()
    return scope
  }

  describe("resourceChanged") {
    it("sends the workspace folders exactly once when a project is added") {
      fireAndSettle(eventFor(delta(mockk<IProject>(), kind = IResourceDelta.ADDED)))

      val captured = slot<DidChangeConfigurationParams>()
      verify(exactly = 1) { languageServer.didChangeConfiguration(capture(captured)) }
      captured.captured.settings shouldBe GitLabLanguageServerConfigurationParams(workspaceFolders = folders)
    }

    it("sends exactly once when a project is removed") {
      fireAndSettle(eventFor(delta(mockk<IProject>(), kind = IResourceDelta.REMOVED)))

      verify(exactly = 1) { languageServer.didChangeConfiguration(any()) }
    }

    it("sends exactly once when a project is opened or closed (CHANGED with the OPEN flag)") {
      fireAndSettle(
        eventFor(delta(mockk<IProject>(), kind = IResourceDelta.CHANGED, flags = IResourceDelta.OPEN))
      )

      verify(exactly = 1) { languageServer.didChangeConfiguration(any()) }
    }

    it("does not send for a project CHANGED without the OPEN flag (plain file-save churn)") {
      val file = delta(mockk<IFile>(), kind = IResourceDelta.CHANGED, flags = IResourceDelta.CONTENT)
      val project = delta(mockk<IProject>(), kind = IResourceDelta.CHANGED, children = listOf(file))

      fireAndSettle(eventFor(project))

      verify(exactly = 0) { languageServer.didChangeConfiguration(any()) }
    }

    it("sends exactly once when two projects change in a single event") {
      fireAndSettle(
        eventFor(
          delta(mockk<IProject>(), kind = IResourceDelta.ADDED),
          delta(mockk<IProject>(), kind = IResourceDelta.ADDED)
        )
      )

      verify(exactly = 1) { languageServer.didChangeConfiguration(any()) }
    }

    it("does not send for a delta whose resource is not a project") {
      fireAndSettle(eventFor(delta(mockk<IFile>(), kind = IResourceDelta.ADDED)))

      verify(exactly = 0) { languageServer.didChangeConfiguration(any()) }
    }

    it("does not cancel the shared scope when reading the workspace folders throws") {
      // Project deletion near shutdown: `workspaceFolders` calls ResourcesPlugin.getWorkspace(),
      // which throws IllegalStateException once the resources bundle winds down. The scope is the
      // SHARED plain-Job scope from WorkspaceModule — an escape cancels every coroutine on it.
      every { workspaceFolders } throws IllegalStateException("Workspace is closed.")
      val swallowUncaught = CoroutineExceptionHandler { _, _ -> }
      val sharedScope = CoroutineScope(Job() + UnconfinedTestDispatcher() + swallowUncaught)
      val listener = ProjectOpenLanguageServerListener(wrapper, sharedScope, Mutex())

      listener.resourceChanged(eventFor(delta(mockk<IProject>(), kind = IResourceDelta.REMOVED)))

      sharedScope.isActive shouldBe true
      verify(exactly = 0) { languageServer.didChangeConfiguration(any()) }
    }

    it("waits for the outbound lock instead of racing whatever else is being sent") {
      // This notification carries `workspaceFolders`, and so does the full configuration. Sent
      // outside the outbound Mutex it interleaves with a full send, and whichever lands last
      // decides the server's folder list — a newly imported project can be dropped again by a
      // full send that was built before it appeared (issue #16).
      val outboundLock = Mutex()
      val scope = TestScope(StandardTestDispatcher())
      val listener = ProjectOpenLanguageServerListener(wrapper, scope, outboundLock)
      outboundLock.tryLock() shouldBe true

      listener.resourceChanged(eventFor(delta(mockk<IProject>(), kind = IResourceDelta.ADDED)))
      scope.testScheduler.advanceUntilIdle()
      verify(exactly = 0) { languageServer.didChangeConfiguration(any()) }

      outboundLock.unlock()
      scope.testScheduler.advanceUntilIdle()
      verify(exactly = 1) { languageServer.didChangeConfiguration(any()) }
    }
  }
})
