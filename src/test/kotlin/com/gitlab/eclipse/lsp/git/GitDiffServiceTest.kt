package com.gitlab.eclipse.lsp.git

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.eclipse.jgit.api.Git
import java.io.File

class GitDiffServiceTest : DescribeSpec({
  lateinit var repository: File
  lateinit var git: Git

  val service = GitDiffService()

  extensions(LoggingKotestExtension)

  beforeEach {
    repository = tempdir()

    if (!repository.exists()) {
      repository.createNewFile()
    }

    git = Git.init().setDirectory(repository).setInitialBranch("main").call()
  }

  afterEach {
    repository.deleteRecursively()
  }

  it("should not return a diff for a repository that does not exist") {
    service.getDiff("file:///does-not-exist") shouldBe null
  }

  it("should get diff content from HEAD") {
    val testFile = repository.newFile("test.txt", "Initial content")

    git.add().addFilepattern("test.txt").call()
    git.commit().setMessage("Initial commit").call()

    testFile.writeText("Modified content")

    val diffContent = service.getDiff(repository.toURI().toASCIIString())

    diffContent shouldContain "-Initial content"
    diffContent shouldContain "+Modified content"
  }

  it("should get diff content from a specific branch") {
    val testFile = repository.newFile("test.txt", "Initial content")

    git.add().addFilepattern("test.txt").call()
    git.commit().setMessage("Initial commit").call()
    git.checkout().setCreateBranch(true).setName("feature-branch").call()

    testFile.writeText("Feature branch content")

    git.add().addFilepattern("test.txt").call()
    git.commit().setMessage("Feature branch commit").call()
    git.checkout().setName("main").call()

    testFile.writeText("Main branch content")

    val diffContent = service.getDiff(repository.toURI().toASCIIString(), "feature-branch")

    diffContent shouldContain "-Feature branch content"
    diffContent shouldContain "+Main branch content"
  }
})

fun File.newFile(fileName: String, content: String): File {
  val file = resolve(fileName)
  file.createNewFile()
  file.writeText(content)
  return file
}
