package com.gitlab.eclipse.views.sidebar.autorefresh

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.mergerequests.CurrentBranchGitReader
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files

/**
 * The HEAD probe's contract (HeadProbe KDoc): `onResult` exactly once per probe, on the UI hop,
 * with Unavailable whenever the read cannot happen or fails — the refresher waits for that answer.
 * The scope runs Unconfined so a probe completes inside the call; the hop runs inline.
 */
class GitHeadProbeTest : StringSpec({
  extensions(LoggingKotestExtension)

  fun answers(probe: GitHeadProbe, gitDir: String): List<HeadKey> {
    val results = mutableListOf<HeadKey>()
    probe.probe(gitDir) { results += it }
    return results
  }

  fun hops(): Pair<MutableList<String>, (() -> Unit) -> Unit> {
    val trace = mutableListOf<String>()
    return trace to { block: () -> Unit ->
      trace += "hop"
      block()
    }
  }

  "answers the branch of a real repository once, through the hop" {
    val dir = Files.createTempDirectory("ci04-probe").toFile()
    try {
      Git.init().setDirectory(dir).setInitialBranch("main").call().close()
      val (trace, hop) = hops()
      val probe = GitHeadProbe(immediateScope(), CurrentBranchGitReader(), hop)

      answers(probe, File(dir, ".git").path) shouldBe listOf(HeadKey.Branch("main"))
      trace shouldBe listOf("hop")
    } finally {
      dir.deleteRecursively()
    }
  }

  "answers Unavailable without reading when the git dir no longer exists (no walk up to an enclosing repository)" {
    val reader = mockk<CurrentBranchGitReader>()
    val (_, hop) = hops()
    val probe = GitHeadProbe(immediateScope(), reader, hop)

    answers(probe, "/no/such/repo/.git") shouldBe listOf(HeadKey.Unavailable)
    verify(exactly = 0) { reader.read(any()) }
  }

  "answers Unavailable once when the scope is already cancelled and the read never runs" {
    val reader = mockk<CurrentBranchGitReader>()
    val (trace, hop) = hops()
    val probe = GitHeadProbe(immediateScope(Job().apply { cancel() }), reader, hop)

    answers(probe, Files.createTempDirectory("ci04-probe-cancelled").toFile().also { it.deleteOnExit() }.path) shouldBe
      listOf(HeadKey.Unavailable)
    trace shouldBe listOf("hop")
    verify(exactly = 0) { reader.read(any()) }
  }

  "answers Unavailable once when the read throws, and the shared scope survives" {
    val reader = mockk<CurrentBranchGitReader>()
    every { reader.read(any()) } throws IllegalStateException("boom")
    val scope = immediateScope()
    val (_, hop) = hops()
    val probe = GitHeadProbe(scope, reader, hop)
    val dir = Files.createTempDirectory("ci04-probe-throws").toFile().also { it.deleteOnExit() }

    answers(probe, dir.path) shouldBe listOf(HeadKey.Unavailable)
    scope.isActive shouldBe true
  }
})
