package com.gitlab.eclipse.snippets

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Real repositories, not mocks: the value of this class is entirely in how JGit's in-core
 * PatchApplier behaves, so a mocked one would only assert that the test author guessed right.
 */
class PatchApplyPlannerTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  fun newRepo(): Pair<File, Git> {
    val dir = createTempDirectory("patch-plan").toFile()
    return dir to Git.init().setDirectory(dir).setInitialBranch("main").call()
  }

  fun commit(git: Git, dir: File, name: String, text: String) {
    File(dir, name).writeText(text)
    git.add().addFilepattern(name).call()
    git.commit().setMessage("m").setAuthor("t", "t@example.com").setSign(false).call()
  }

  fun openRepo(dir: File) = FileRepositoryBuilder().setGitDir(File(dir, ".git")).build()

  fun plan(dir: File, patch: String) = openRepo(dir).use { PatchApplyPlanner().plan(it, patch) }

  val modifyA = """
    diff --git a/A.txt b/A.txt
    --- a/A.txt
    +++ b/A.txt
    @@ -1 +1 @@
    -a
    +b

  """.trimIndent()

  describe("plan") {
    it("materialises the post-image of a modified file without touching the working tree") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }

      val result = plan(dir, modifyA)

      result.shouldBeInstanceOf<PatchPlan.Ok>()
      val change = result.changes.single()
      change.path shouldBe "A.txt"
      change.kind shouldBe PatchChangeKind.MODIFY
      String(change.content!!) shouldBe "b\n"
      change.mode shouldBe FileMode.REGULAR_FILE
      // In-core only: nothing on disk moved.
      File(dir, "A.txt").readText() shouldBe "a\n"
    }

    it("reports an added file as an ADD carrying its full content") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      val patch = """
        diff --git a/New.txt b/New.txt
        new file mode 100644
        --- /dev/null
        +++ b/New.txt
        @@ -0,0 +1 @@
        +created

      """.trimIndent()

      val result = plan(dir, patch)

      result.shouldBeInstanceOf<PatchPlan.Ok>()
      result.changes.single().kind shouldBe PatchChangeKind.ADD
      String(result.changes.single().content!!) shouldBe "created\n"
    }

    it("reports a deleted file as a DELETE with no content") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      val patch = """
        diff --git a/A.txt b/A.txt
        deleted file mode 100644
        --- a/A.txt
        +++ /dev/null
        @@ -1 +0,0 @@
        -a

      """.trimIndent()

      val result = plan(dir, patch)

      result.shouldBeInstanceOf<PatchPlan.Ok>()
      result.changes.single().kind shouldBe PatchChangeKind.DELETE
      result.changes.single().content shouldBe null
    }

    it("keeps the symlink mode of an added link") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      val patch = """
        diff --git a/link b/link
        new file mode 120000
        --- /dev/null
        +++ b/link
        @@ -0,0 +1 @@
        +A.txt
        \ No newline at end of file

      """.trimIndent()

      val result = plan(dir, patch)

      result.shouldBeInstanceOf<PatchPlan.Ok>()
      result.changes.single().mode shouldBe FileMode.SYMLINK
      String(result.changes.single().content!!) shouldBe "A.txt"
    }

    it("keeps the executable bit of a mode-only change") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      val patch = """
        diff --git a/A.txt b/A.txt
        old mode 100644
        new mode 100755

      """.trimIndent()

      val result = plan(dir, patch)

      result.shouldBeInstanceOf<PatchPlan.Ok>()
      result.changes.single().mode shouldBe FileMode.EXECUTABLE_FILE
    }

    it("rejects a patch that carries a binary hunk") {
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

      plan(dir, patch) shouldBe PatchPlan.BinaryNotSupported
    }

    it("rejects a patch that adds a submodule before applying anything") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      val patch = """
        diff --git a/sub b/sub
        new file mode 160000
        index 0000000000000000000000000000000000000000..1111111111111111111111111111111111111111
        --- /dev/null
        +++ b/sub
        @@ -0,0 +1 @@
        +Subproject commit 1111111111111111111111111111111111111111

      """.trimIndent()

      plan(dir, patch) shouldBe PatchPlan.GitlinkNotSupported
    }

    it("aborts when the target path carries a staged change") {
      val (dir, git) = newRepo()
      git.use {
        commit(it, dir, "A.txt", "a\n")
        File(dir, "A.txt").writeText("staged\n")
        it.add().addFilepattern("A.txt").call()
      }

      plan(dir, modifyA) shouldBe PatchPlan.StagedChanges(1)
    }

    it("aborts when the target path carries an unstaged working-tree change") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      // The patch context still matches HEAD, so it would apply — over the user's edit.
      File(dir, "A.txt").writeText("a\nmine\n")

      plan(dir, modifyA) shouldBe PatchPlan.DirtyWorkTree(1)
    }

    it("allows a patch when the dirty file is not one it touches") {
      val (dir, git) = newRepo()
      git.use {
        commit(it, dir, "A.txt", "a\n")
        commit(it, dir, "B.txt", "b\n")
      }
      File(dir, "B.txt").writeText("mine\n")

      plan(dir, modifyA).shouldBeInstanceOf<PatchPlan.Ok>()
    }

    it("reports a conflicting patch as a failure and writes nothing") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "totally different\n") }

      val result = plan(dir, modifyA)

      result.shouldBeInstanceOf<PatchPlan.ApplyFailed>()
      File(dir, "A.txt").readText() shouldBe "totally different\n"
    }

    it("reports a repository without a commit") {
      val (dir, git) = newRepo()
      git.use { }

      plan(dir, modifyA) shouldBe PatchPlan.NoHead
    }

    it("reports a patch that parses to no files") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }

      plan(dir, "not a patch at all\n") shouldBe PatchPlan.Empty
    }

    it("reports a patch that applies to nothing as empty rather than Ok") {
      val (dir, git) = newRepo()
      git.use { commit(it, dir, "A.txt", "a\n") }
      val patch = """
        diff --git a/A.txt b/A.txt
        --- a/A.txt
        +++ b/A.txt
        @@ -1 +1 @@
        -a
        +a

      """.trimIndent()

      plan(dir, patch) shouldBe PatchPlan.Empty
    }
  }
})
