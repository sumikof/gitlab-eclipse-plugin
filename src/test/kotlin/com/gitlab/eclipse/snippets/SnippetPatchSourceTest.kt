package com.gitlab.eclipse.snippets

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.eclipse.jgit.api.Git
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * Real repositories, not mocks: the value of this class is entirely in how JGit behaves, so a
 * mocked Git would only assert that the test author guessed right.
 */
class SnippetPatchSourceTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  fun newRepo(): Pair<File, Git> {
    val dir = createTempDirectory("snippet-patch").toFile()
    val git = Git.init().setDirectory(dir).setInitialBranch("main").call()
    return dir to git
  }

  fun commitFile(git: Git, dir: File, name: String, text: String, message: String) {
    File(dir, name).writeText(text)
    git.add().addFilepattern(name).call()
    git.commit().setMessage(message).setAuthor("t", "t@example.com").setSign(false).call()
  }

  describe("read") {
    it("produces a working-tree diff against HEAD and names the branch and commit") {
      val (dir, git) = newRepo()
      git.use {
        commitFile(it, dir, "A.kt", "a\n", "initial")
        File(dir, "A.kt").writeText("b\n")

        val source = SnippetPatchSource().read(File(dir, ".git"))!!

        source.diff shouldContain "A.kt"
        source.diff shouldContain "-a"
        source.diff shouldContain "+b"
        source.commitDescriptor shouldStartWith "branch main (commit: "
      }
    }

    it("reports an empty diff when the working tree matches HEAD") {
      val (dir, git) = newRepo()
      git.use {
        commitFile(it, dir, "A.kt", "a\n", "initial")

        SnippetPatchSource().read(File(dir, ".git"))!!.diff shouldBe ""
      }
    }

    it("returns null when the repository has no commit yet") {
      val (dir, git) = newRepo()
      git.use {
        File(dir, "A.kt").writeText("a\n")

        SnippetPatchSource().read(File(dir, ".git")) shouldBe null
      }
    }
  }
})
