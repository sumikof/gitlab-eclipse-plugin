package com.gitlab.eclipse.lsp.capabilities

import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.utils.workspaceFolders
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.eclipse.core.internal.resources.Workspace
import org.eclipse.core.resources.IResource
import org.eclipse.core.resources.IResourceChangeEvent
import org.eclipse.core.resources.IResourceDelta
import org.eclipse.core.resources.IResourceDeltaVisitor
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.lsp4j.*
import java.net.URI

@OptIn(ExperimentalCoroutinesApi::class)
class DidChangeWatchedFileCapabilityTest : DescribeSpec({
  val workspace = mockk<Workspace>(relaxed = true)
  val projectWorkspaceFolders = listOf(
    WorkspaceFolder("file:/project", "project")
  )

  val languageServer = mockk<GitLabLanguageServer>(relaxed = true)
  val languageServerWrapper = mockk<GitLabLanguageServerWrapper>()

  val coroutineScope = TestScope(UnconfinedTestDispatcher())

  // Create a capability that instantly sends events to Language Server.
  lateinit var capability: DidChangeWatchedFileCapability

  beforeSpec {
    mockkStatic(ResourcesPlugin::getWorkspace)
    mockkStatic("com.gitlab.eclipse.lsp.utils.ProjectsWorkspaceFolderKt")
  }

  beforeEach {
    every { ResourcesPlugin.getWorkspace() } returns workspace
    every { languageServerWrapper.languageServer } returns languageServer
    every { workspaceFolders } returns projectWorkspaceFolders

    // Interested in Create events for Kotlin File Events
    // Interested in all events for Java File Events
    capability = DidChangeWatchedFileCapability(languageServerWrapper, coroutineScope, sendBatchDelayInMs = 10L).apply {
      register(
        id = "123",
        options = JsonObject().apply {
          add(
            "watchers",
            JsonArray().apply {
              add(
                JsonObject().apply {
                  addProperty("globPattern", "**/*.kt")
                  addProperty("kind", WatchKind.Change)
                }
              )
              add(
                JsonObject().apply {
                  addProperty("globPattern", "**/*.java")
                }
              )
            }
          )
        }
      )
    }
  }

  afterEach {
    clearAllMocks()
  }

  afterSpec { unmockkAll() }

  it("stops sending events after all watchers are unregistered") {
    capability.documentChanged("file:/project/test.java")
    coroutineScope.wait(25)
    verify(exactly = 1) { languageServer.didChangeWatchedFiles(any()) }

    clearMocks(languageServer)

    capability.unregister("123")
    capability.documentChanged("file:/project/test.java")
    coroutineScope.wait(25)
    verify(exactly = 0) { languageServer.didChangeWatchedFiles(any()) }
  }

  it("can register new watchers after existing ones are unregistered") {
    capability.unregister("123")
    capability.documentChanged("file:/project/test.kt")
    coroutineScope.wait(25)
    verify(exactly = 0) { languageServer.didChangeWatchedFiles(any()) }

    capability.register(
      id = "456",
      options = JsonObject().apply {
        add(
          "watchers",
          JsonArray().apply {
            add(
              JsonObject().apply {
                addProperty("globPattern", "**/*.kt")
                addProperty("kind", WatchKind.Change)
              }
            )
          }
        )
      }
    )

    capability.documentChanged("file:/project/test.kt")
    coroutineScope.wait(25)
    verify(exactly = 1) { languageServer.didChangeWatchedFiles(any()) }
  }

  it("can unregister all watchers") {
    capability.documentChanged("file:/project/test.kt")
    coroutineScope.wait(25)
    verify(exactly = 1) { languageServer.didChangeWatchedFiles(any()) }

    capability.unregisterAll()
    capability.documentChanged("file:/project/test.kt")
    coroutineScope.wait(25)
    verify(exactly = 1) { languageServer.didChangeWatchedFiles(any()) }

    capability.documentChanged("file:/project/test.java")
    coroutineScope.wait(25)
    verify(exactly = 1) { languageServer.didChangeWatchedFiles(any()) }
  }

  describe("documentChanged") {
    it("sends events only for watched files when they are changed") {
      capability.documentChanged("file:/project/test.kt")
      coroutineScope.wait(25)
      verify(exactly = 1) { languageServer.didChangeWatchedFiles(any()) }

      capability.documentChanged("file:/project/test.java")
      coroutineScope.wait(25)
      verify(exactly = 2) { languageServer.didChangeWatchedFiles(any()) }

      capability.documentChanged("file:/project/test.go")
      coroutineScope.wait(25)
      verify(exactly = 2) { languageServer.didChangeWatchedFiles(any()) }
    }
  }

  describe("resourceChanged") {
    it("sends events for watched files when they are changed") {
      capability.resourceChanged(
        mockk<IResourceChangeEvent>(relaxed = true).apply {
          every { delta.kind } returns IResourceDelta.CHANGED
          every { delta.affectedChildren } returns emptyArray()
          every { delta.resource } returns mockk<IResource>(relaxed = true).apply {
            every { locationURI } returns URI("file:/project/test.kt")
          }
          every { delta.accept(any()) } answers { firstArg<IResourceDeltaVisitor>().visit(delta) }
        }
      )

      coroutineScope.wait(25)
      verify(exactly = 1) {
        languageServer.didChangeWatchedFiles(
          DidChangeWatchedFilesParams(
            listOf(
              FileEvent("file:/project/test.kt", FileChangeType.Changed)
            )
          )
        )
      }
    }

    it("sends events for watched files when they are created") {
      capability.resourceChanged(
        mockk<IResourceChangeEvent>(relaxed = true).apply {
          every { delta.kind } returns IResourceDelta.ADDED
          every { delta.affectedChildren } returns emptyArray()
          every { delta.resource } returns mockk<IResource>(relaxed = true).apply {
            every { locationURI } returns URI("file:/project/test.java")
          }
          every { delta.accept(any()) } answers { firstArg<IResourceDeltaVisitor>().visit(delta) }
        }
      )

      coroutineScope.wait(25)
      verify(exactly = 1) {
        languageServer.didChangeWatchedFiles(
          DidChangeWatchedFilesParams(
            listOf(
              FileEvent("file:/project/test.java", FileChangeType.Created)
            )
          )
        )
      }
    }

    it("sends events for watched files when they are deleted") {
      capability.resourceChanged(
        mockk<IResourceChangeEvent>(relaxed = true).apply {
          every { delta.kind } returns IResourceDelta.REMOVED
          every { delta.affectedChildren } returns emptyArray()
          every { delta.resource } returns mockk<IResource>(relaxed = true).apply {
            every { locationURI } returns URI("file:/project/test.java")
          }
          every { delta.accept(any()) } answers { firstArg<IResourceDeltaVisitor>().visit(delta) }
        }
      )

      coroutineScope.wait(25)
      verify(exactly = 1) {
        languageServer.didChangeWatchedFiles(
          DidChangeWatchedFilesParams(
            listOf(
              FileEvent("file:/project/test.java", FileChangeType.Deleted)
            )
          )
        )
      }
    }

    it("should not send events for the workspace folders") {
      capability.register(
        id = "999",
        JsonObject().apply {
          add(
            "watchers",
            JsonArray().apply {
              add(
                JsonObject().apply {
                  addProperty("globPattern", "**/*")
                  addProperty("kind", WatchKind.Change)
                }
              )
            }
          )
        }
      )

      capability.resourceChanged(
        mockk<IResourceChangeEvent>(relaxed = true).apply {
          every { delta.kind } returns IResourceDelta.CHANGED
          every { delta.affectedChildren } returns emptyArray()
          every { delta.resource } returns mockk<IResource>(relaxed = true).apply {
            every { locationURI } returns URI("file:/project")
          }
          every { delta.accept(any()) } answers { firstArg<IResourceDeltaVisitor>().visit(delta) }
        }
      )

      coroutineScope.wait(25)
      verify(exactly = 0) {
        languageServer.didChangeWatchedFiles(any())
      }
    }

    it("should not send changed events for parent folder when the child is changed") {
      val childDelta = mockk<IResourceDelta>(relaxed = true).apply {
        every { kind } returns IResourceDelta.CHANGED
        every { affectedChildren } returns emptyArray()
        every { resource } returns mockk<IResource>(relaxed = true).apply {
          every { locationURI } returns URI("file:/project/src/test.kt")
        }
      }
      val parentDelta = mockk<IResourceDelta>(relaxed = true).apply {
        every { kind } returns IResourceDelta.CHANGED
        every { affectedChildren } returns arrayOf(childDelta)
        every { resource } returns mockk<IResource>(relaxed = true).apply {
          every { locationURI } returns URI("file:/project/src")
        }
        every { accept(any()) } answers { firstArg<IResourceDeltaVisitor>().visit(childDelta) }
      }

      capability.resourceChanged(
        mockk<IResourceChangeEvent>(relaxed = true).apply {
          every { delta } returns parentDelta
        }
      )

      coroutineScope.wait(25)
      verify(exactly = 1) {
        languageServer.didChangeWatchedFiles(
          DidChangeWatchedFilesParams(
            listOf(
              FileEvent("file:/project/src/test.kt", FileChangeType.Changed)
            )
          )
        )
      }
    }
  }
})

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.wait(millis: Long) = testScheduler.advanceTimeBy(millis)
