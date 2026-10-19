package com.gitlab.eclipse.mergerequests.diff

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.JGitInternalException
import org.eclipse.jgit.dircache.DirCacheEditor
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

private const val MAX = 1024

private fun Git.commitFile(name: String, content: String, message: String): RevCommit {
  File(repository.workTree, name).apply { parentFile.mkdirs() }.writeText(content)
  add().addFilepattern(name).call()
  return commit().setMessage(message).setSign(false).call()
}

private fun LocalBlob.text(): String = shouldBeInstanceOf<LocalBlob.Found>().bytes.decodeToString()

class LocalBlobReaderTest : DescribeSpec({
  describe("LocalBlobReader over one repository") {
    val dir = tempdir()
    val git = Git.init().setDirectory(dir).setInitialBranch("main").call()
    val base = git.commitFile("f.txt", "base", "A")
    git.commitFile("dir/inner.txt", "inner", "dir")
    val head = git.commitFile("f.txt", "head", "B")
    // The checked-out branch and the working tree both disagree with base and head.
    git.checkout().setCreateBranch(true).setName("other").call()
    git.commitFile("f.txt", "branch", "C")
    File(dir, "f.txt").writeText("dirty")
    val reader = LocalBlobReader { listOf(git.repository.directory) }

    it("reads the blob at the requested commit, never the working tree or the current branch") {
      reader.read(SideRef("f.txt", base.name), MAX).text() shouldBe "base"
      reader.read(SideRef("f.txt", head.name), MAX).text() shouldBe "head"
    }

    it("reads a nested path") {
      reader.read(SideRef("dir/inner.txt", head.name), MAX).text() shouldBe "inner"
    }

    it("leaves HEAD, the index and the refs untouched") {
      val repository = git.repository
      val headRef = repository.exactRef("HEAD").target.name to repository.resolve("HEAD")
      val index = File(repository.directory, "index").readBytes()
      val refs = repository.refDatabase.refs.associate { it.name to it.objectId }

      reader.read(SideRef("f.txt", base.name), MAX)
      reader.read(SideRef("missing.txt", head.name), MAX)

      (repository.exactRef("HEAD").target.name to repository.resolve("HEAD")) shouldBe headRef
      File(repository.directory, "index").readBytes().contentEquals(index) shouldBe true
      repository.refDatabase.refs.associate { it.name to it.objectId } shouldBe refs
      File(dir, "f.txt").readText() shouldBe "dirty"
    }

    it("is Unavailable for a commit the object database does not hold") {
      reader.read(SideRef("f.txt", "1".repeat(40)), MAX) shouldBeSameInstanceAs LocalBlob.Unavailable
    }

    it("is Unavailable for a 64-hex SHA, which JGit cannot address") {
      reader.read(SideRef("f.txt", "a".repeat(64)), MAX) shouldBeSameInstanceAs LocalBlob.Unavailable
    }

    it("is NotInTree for a path missing from the commit") {
      reader.read(SideRef("missing.txt", head.name), MAX) shouldBeSameInstanceAs LocalBlob.NotInTree
    }

    it("is NotInTree for a directory path") {
      reader.read(SideRef("dir", head.name), MAX) shouldBeSameInstanceAs LocalBlob.NotInTree
    }

    it("is TooLarge when the blob exceeds the cap, and Found at exactly the cap") {
      reader.read(SideRef("f.txt", head.name), 3) shouldBeSameInstanceAs LocalBlob.TooLarge
      reader.read(SideRef("f.txt", head.name), 4).text() shouldBe "head"
    }
  }

  describe("LocalBlobReader on a gitlink") {
    it("reports Gitlink for a submodule entry") {
      val git = Git.init().setDirectory(tempdir()).setInitialBranch("main").call()
      git.commitFile("README", "r", "init")
      val cache = git.repository.lockDirCache()
      val editor = cache.editor()
      editor.add(
        object : DirCacheEditor.PathEdit("sub") {
          override fun apply(entry: DirCacheEntry) {
            entry.fileMode = FileMode.GITLINK
            entry.setObjectId(ObjectId.fromString("2".repeat(40)))
          }
        },
      )
      editor.commit()
      val withSubmodule = git.commit().setMessage("submodule").setSign(false).call()

      LocalBlobReader { listOf(git.repository.directory) }
        .read(SideRef("sub", withSubmodule.name), MAX) shouldBeSameInstanceAs LocalBlob.Gitlink
    }
  }

  describe("LocalBlobReader over several candidates") {
    it("reads from the first repository that holds the commit, skipping ones that do not or cannot open") {
      val first = Git.init().setDirectory(tempdir()).setInitialBranch("main").call()
      first.commitFile("f.txt", "first", "1")
      val second = Git.init().setDirectory(tempdir()).setInitialBranch("main").call()
      val commit = second.commitFile("f.txt", "second", "2")
      val missing = File(tempdir(), "nope/.git")

      LocalBlobReader { listOf(missing, first.repository.directory, second.repository.directory) }
        .read(SideRef("f.txt", commit.name), MAX).text() shouldBe "second"
    }

    it("is Unavailable when there are no candidates") {
      LocalBlobReader { emptyList() }.read(SideRef("f.txt", "1".repeat(40)), MAX) shouldBeSameInstanceAs
        LocalBlob.Unavailable
    }
  }

  describe("LocalBlobReader on a candidate that throws a runtime exception (R5)") {
    fun brokenRepository(): Repository = mockk<Repository>(relaxed = true).also {
      every { it.objectDatabase.has(any()) } throws JGitInternalException("corrupt pack")
    }

    fun realOpen(dir: File): Repository = FileRepositoryBuilder().setGitDir(dir).setMustExist(true).build()

    it("skips it and reads from the next candidate") {
      val good = Git.init().setDirectory(tempdir()).setInitialBranch("main").call()
      val commit = good.commitFile("f.txt", "good", "1")
      val broken = File(tempdir(), ".git")

      val reader = LocalBlobReader(
        gitDirs = { listOf(broken, good.repository.directory) },
        openRepository = { dir -> if (dir == broken) brokenRepository() else realOpen(dir) },
      )

      reader.read(SideRef("f.txt", commit.name), MAX).text() shouldBe "good"
    }

    it("is Unavailable (so REST is tried) when it is the only candidate") {
      val broken = File(tempdir(), ".git")

      val reader = LocalBlobReader(gitDirs = { listOf(broken) }, openRepository = { brokenRepository() })

      reader.read(SideRef("f.txt", "1".repeat(40)), MAX) shouldBeSameInstanceAs LocalBlob.Unavailable
    }
  }
})
