package com.gitlab.eclipse.handlers

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.navigation.ClipboardTarget
import com.gitlab.eclipse.navigation.ClipboardWriter
import com.gitlab.eclipse.navigation.GitLabProjectUrlResolver
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.eclipse.core.resources.IFile
import org.eclipse.core.runtime.Path
import org.eclipse.swt.SWTError
import org.eclipse.swt.dnd.DND
import java.io.File

private const val BRANCH_URL = "https://gitlab.example.com/g/p/-/blob/feature%2Fx/src/a.kt#L2-4"
private const val NOT_IN_REPO = "The current file is not in the project repository."
private const val DETACHED = "The repository seems to be in a detached HEAD state. Please check out a branch."

/** NAV-03: [CopyLinkToActiveFileOnBranchHandler.run] copies only a resolved link, and says so only once it landed. */
class CopyLinkToActiveFileOnBranchHandlerTest : StringSpec({
  extensions(LoggingKotestExtension)

  val written = mutableListOf<String>()
  val notices = mutableListOf<String>()
  val ioFile = File("/work/repo/src/a.kt")

  beforeTest {
    written.clear()
    notices.clear()
  }

  fun editorFile(location: File? = ioFile): IFile = mockk {
    every { this@mockk.location } returns location?.let { Path.fromOSString(it.path) }
    every { name } returns "a.kt"
  }

  fun handler(resolver: GitLabProjectUrlResolver, failing: Boolean = false): CopyLinkToActiveFileOnBranchHandler {
    val target = object : ClipboardTarget {
      override fun setText(text: String) {
        if (failing) throw SWTError(DND.ERROR_CANNOT_SET_CLIPBOARD, "clipboard busy")
        written += text
      }

      override fun dispose() = Unit
    }
    return CopyLinkToActiveFileOnBranchHandler(
      platformUtils = mockk(),
      resolver = resolver,
      clipboard = ClipboardWriter(onUiThread = { it.run() }, openClipboard = { target }),
      notify = { notices += it },
      launch = { block -> runBlocking { block() } },
    )
  }

  fun resolving(result: GitLabProjectUrlResolver.Resolution): GitLabProjectUrlResolver = mockk {
    every { resolveBranchBlobUrl(ioFile, 1, 3) } returns result
  }

  "a resolved branch link is copied once and announced once it landed" {
    handler(resolving(GitLabProjectUrlResolver.Resolution.Ok(BRANCH_URL))).run(editorFile(), 1, 3)

    written shouldContainExactly listOf(BRANCH_URL)
    notices shouldContainExactly listOf(ClipboardWriter.COPIED_TO_CLIPBOARD)
  }

  "a warning is only shown, nothing is copied" {
    handler(resolving(GitLabProjectUrlResolver.Resolution.Warn(DETACHED))).run(editorFile(), 1, 3)

    written.shouldBeEmpty()
    notices shouldContainExactly listOf(DETACHED)
  }

  "a copy that did not land says nothing" {
    handler(resolving(GitLabProjectUrlResolver.Resolution.Ok(BRANCH_URL)), failing = true).run(editorFile(), 1, 3)

    written.shouldBeEmpty()
    notices.shouldBeEmpty()
  }

  "no open file only notifies, without resolving" {
    val resolver = mockk<GitLabProjectUrlResolver>()

    handler(resolver).run(null, 1, 3)

    notices shouldContainExactly listOf("GitLab: No open file.")
    written.shouldBeEmpty()
    verify(exactly = 0) { resolver.resolveBranchBlobUrl(any(), any(), any()) }
  }

  "a file without a local location warns NOT_IN_REPO, without resolving" {
    val resolver = mockk<GitLabProjectUrlResolver>()

    handler(resolver).run(editorFile(location = null), 1, 3)

    notices shouldContainExactly listOf(NOT_IN_REPO)
    written.shouldBeEmpty()
    verify(exactly = 0) { resolver.resolveBranchBlobUrl(any(), any(), any()) }
  }
})
