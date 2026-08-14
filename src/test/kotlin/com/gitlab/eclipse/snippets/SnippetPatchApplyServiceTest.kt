package com.gitlab.eclipse.snippets

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.mergerequests.GitOperationGuard
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.api.Git
import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory

/** End to end over a real repository: plan, write, back up and index update together. */
class SnippetPatchApplyServiceTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  fun newRepo(): Pair<File, Git> {
    val dir = createTempDirectory("patch-apply").toFile()
    return dir to Git.init().setDirectory(dir).setInitialBranch("main").call()
  }

  fun commit(git: Git, dir: File, name: String, text: String) {
    File(dir, name).writeText(text)
    git.add().addFilepattern(name).call()
    git.commit().setMessage("m").setAuthor("t", "t@example.com").setSign(false).call()
  }

  fun newService(
    guard: GitOperationGuard = GitOperationGuard(),
    quarantineRoot: File = createTempDirectory("q").toFile(),
    now: Instant = Instant.EPOCH,
  ) = SnippetPatchApplyService(guard, PatchQuarantine(quarantineRoot) { now })

  val modifyA = """
    diff --git a/A.txt b/A.txt
    --- a/A.txt
    +++ b/A.txt
    @@ -1 +1 @@
    -a
    +b

  """.trimIndent()

  describe("apply") {
    it("applies the patch to the work tree and stages it") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }

      val outcome = newService().apply(File(dir, ".git"), dir, modifyA)

      outcome shouldBe PatchApplyOutcome.Applied(1, 0)
      File(dir, "A.txt").readText() shouldBe "b\n"
      Git.open(dir).use { it.status().call().modified shouldBe emptySet() }
      Git.open(dir).use { it.status().call().changed shouldBe setOf("A.txt") }
    }

    it("leaves a recoverable pre-image behind and marks the session complete") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      val root = createTempDirectory("q").toFile()

      newService(quarantineRoot = root).apply(File(dir, ".git"), dir, modifyA)

      val session = root.listFiles()!!.single()
      File(session, "COMPLETE").exists() shouldBe true
      session.walkTopDown().filter { it.isFile }.any { it.readText() == "a\n" } shouldBe true
    }

    it("changes nothing when the patch does not apply") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "different\n") }

      val outcome = newService().apply(File(dir, ".git"), dir, modifyA)

      outcome.shouldBeInstanceOf<PatchApplyOutcome.PatchRejected>()
      File(dir, "A.txt").readText() shouldBe "different\n"
      Git.open(dir).use { it.status().call().isClean shouldBe true }
    }

    it("reports Busy when another guarded operation holds the repository") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      val guard = GitOperationGuard()
      val gitDir = File(dir, ".git")

      val outcome = guard.withRepo(gitDir.path) {
        newService(guard).apply(gitDir, dir, modifyA)
      }

      outcome shouldBe PatchApplyOutcome.Busy
      File(dir, "A.txt").readText() shouldBe "a\n"
    }

    it("aborts without writing when the target path has a staged change") {
      val (dir, git) = newRepo()
      git.use {
        commit(it, dir, "A.txt", "a\n")
        File(dir, "A.txt").writeText("staged\n")
        it.add().addFilepattern("A.txt").call()
      }

      val outcome = newService().apply(File(dir, ".git"), dir, modifyA)

      outcome shouldBe PatchApplyOutcome.StagedChanges(1)
      File(dir, "A.txt").readText() shouldBe "staged\n"
    }

    it("refuses to overwrite an uncommitted working-tree change") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      File(dir, "A.txt").writeText("a\nmine\n")

      val outcome = newService().apply(File(dir, ".git"), dir, modifyA)

      outcome shouldBe PatchApplyOutcome.DirtyWorkTree(1)
      File(dir, "A.txt").readText() shouldBe "a\nmine\n"
    }

    it("refuses to start when the backup area cannot make room") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      val root = createTempDirectory("q").toFile()
      val now = Instant.parse("2026-01-31T00:00:00Z")
      repeat(PatchQuarantine.MAX_ENTRIES) { index -> File(root, "${now.toEpochMilli()}-$index").mkdirs() }

      val outcome = newService(quarantineRoot = root, now = now).apply(File(dir, ".git"), dir, modifyA)

      outcome shouldBe PatchApplyOutcome.BackupUnavailable
      File(dir, "A.txt").readText() shouldBe "a\n"
    }

    it("reports a binary patch without touching anything") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      val patch = """
        diff --git a/bin.dat b/bin.dat
        new file mode 100644
        index 0000000000000000000000000000000000000000..d95f3ad14dee633a758d2e331151e950dd13e4ed
        GIT binary patch
        literal 4
        Lc${'$'}@<O0RR91

      """.trimIndent()

      newService().apply(File(dir, ".git"), dir, patch) shouldBe PatchApplyOutcome.BinaryNotSupported
    }

    it("reports a repository without a commit") {
      val (dir, git) = newRepo()
      git.use { }

      newService().apply(File(dir, ".git"), dir, modifyA) shouldBe PatchApplyOutcome.NoHead
    }

    it("reports a failure by type when the repository cannot be opened") {
      val missing = createTempDirectory("missing").toFile()

      val outcome = newService().apply(File(missing, ".git"), missing, modifyA)

      outcome.shouldBeInstanceOf<PatchApplyOutcome.Failed>()
      outcome.type.isNotBlank() shouldBe true
    }

    it("applies a multi-file patch as one unit") {
      val (dir, git) = newRepo()
      git.use {
        commit(it, dir, "A.txt", "a\n")
        commit(it, dir, "B.txt", "b\n")
      }
      val patch = """
        diff --git a/A.txt b/A.txt
        --- a/A.txt
        +++ b/A.txt
        @@ -1 +1 @@
        -a
        +A
        diff --git a/B.txt b/B.txt
        --- a/B.txt
        +++ b/B.txt
        @@ -1 +1 @@
        -b
        +B

      """.trimIndent()

      newService().apply(File(dir, ".git"), dir, patch) shouldBe PatchApplyOutcome.Applied(2, 0)

      File(dir, "A.txt").readText() shouldBe "A\n"
      File(dir, "B.txt").readText() shouldBe "B\n"
    }

    it("is not idempotent: applying the same patch twice fails the second time") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      val service = newService()
      service.apply(File(dir, ".git"), dir, modifyA)

      // The first apply staged its result, so the second run stops on the staged-change gate
      // before it can even try to apply — either way nothing is silently applied twice.
      val second = service.apply(File(dir, ".git"), dir, modifyA)

      second shouldBe PatchApplyOutcome.StagedChanges(1)
      File(dir, "A.txt").readText() shouldBe "b\n"
    }
  }
})
