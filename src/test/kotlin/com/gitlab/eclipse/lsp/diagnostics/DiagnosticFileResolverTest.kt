package com.gitlab.eclipse.lsp.diagnostics

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.IWorkspace
import org.eclipse.core.resources.IWorkspaceRoot
import org.eclipse.core.resources.ResourcesPlugin

private fun file(exists: Boolean): IFile = mockk<IFile>().also { every { it.exists() } returns exists }

class DiagnosticFileResolverTest : DescribeSpec({
  lateinit var root: IWorkspaceRoot

  beforeEach {
    root = mockk()
    val workspace = mockk<IWorkspace>()
    mockkStatic(ResourcesPlugin::class)
    every { ResourcesPlugin.getWorkspace() } returns workspace
    every { workspace.root } returns root
  }

  afterEach { unmockkStatic(ResourcesPlugin::class) }

  describe("resolve") {
    // The same file on disk can be linked into several projects, and every one of them has to show
    // the finding, so all hits are returned rather than just the first one.
    it("returns every workspace file mapped to the location") {
      val first = file(exists = true)
      val second = file(exists = true)
      every { root.findFilesForLocationURI(any()) } returns arrayOf(first, second)

      DiagnosticFileResolver.resolve("/p/a.kt") shouldContainExactly listOf(first, second)
    }

    it("drops files that are mapped but no longer exist") {
      every { root.findFilesForLocationURI(any()) } returns arrayOf(file(exists = false))

      DiagnosticFileResolver.resolve("/p/a.kt").shouldBeEmpty()
    }

    it("returns nothing when the location is outside the workspace") {
      every { root.findFilesForLocationURI(any()) } returns emptyArray()

      DiagnosticFileResolver.resolve("/p/a.kt").shouldBeEmpty()
    }

    it("returns nothing when the workspace cannot answer") {
      every { root.findFilesForLocationURI(any()) } throws IllegalStateException("workspace closed")

      DiagnosticFileResolver.resolve("/p/a.kt").shouldBeEmpty()
    }
  }
})
