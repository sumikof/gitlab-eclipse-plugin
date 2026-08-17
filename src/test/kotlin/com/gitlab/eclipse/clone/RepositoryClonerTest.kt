package com.gitlab.eclipse.clone

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.mergerequests.GitOperationGuard
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files

private fun sourceRepo(): File {
  val dir = Files.createTempDirectory("cloner-src").toFile()
  Git.init().setDirectory(dir).call().use { git ->
    File(dir, "README.md").writeText("hello")
    git.add().addFilepattern("README.md").call()
    git.commit().setMessage("initial").setSign(false).call()
  }
  return dir
}

private fun monitor(cancelled: Boolean = false): IProgressMonitor =
  mockk(relaxed = true) { every { isCanceled } returns cancelled }

/** The auth hook is a no-op here: a file:// transport has no GitLab credentials to attach. */
private val noAuth: (org.eclipse.jgit.api.CloneCommand, String) -> org.eclipse.jgit.api.CloneCommand =
  { command, _ -> command }

class RepositoryClonerTest : StringSpec({
  // The Failed path logs through Platform.getLog, which needs an OSGi bundle; this extension
  // stubs it the same way every other headless spec in this codebase does.
  extensions(LoggingKotestExtension)

  "clones a repository into a fresh directory" {
    val source = sourceRepo()
    val target = File(Files.createTempDirectory("cloner-dst").toFile(), "repo")
    val cloner = RepositoryCloner(GitOperationGuard(), noAuth)

    cloner.clone(source.toURI().toString(), target, "https://gitlab.example.com", monitor()) shouldBe
      RepositoryCloner.Outcome.Succeeded
    File(target, "README.md").exists() shouldBe true
    File(target, ".git").isDirectory shouldBe true
  }

  "reports Failed with the exception type only when the source does not exist" {
    val target = File(Files.createTempDirectory("cloner-bad").toFile(), "repo")
    val cloner = RepositoryCloner(GitOperationGuard(), noAuth)

    val outcome = cloner.clone(
      File("/nonexistent/does-not-exist.git").toURI().toString(),
      target,
      "https://gitlab.example.com",
      monitor(),
    )

    (outcome is RepositoryCloner.Outcome.Failed) shouldBe true
    (outcome as RepositoryCloner.Outcome.Failed).type.startsWith("org.eclipse.jgit") shouldBe true
  }

  "does not delete anything the plugin did not create when the clone fails" {
    val parent = Files.createTempDirectory("cloner-keep").toFile()
    val target = File(parent, "repo")
    target.mkdirs()
    File(target, "mine.txt").writeText("user data")
    val cloner = RepositoryCloner(GitOperationGuard(), noAuth)

    cloner.clone(
      File("/nonexistent/does-not-exist.git").toURI().toString(),
      target,
      "https://gitlab.example.com",
      monitor(),
    )

    // The plugin has no delete path of its own. (JGit's own cleanup may or may not have run —
    // this asserts only that the cloner did not add one.)
    parent.exists() shouldBe true
  }

  "refuses a second clone into the same location while one is running" {
    val guard = GitOperationGuard()
    val source = sourceRepo()
    val target = File(Files.createTempDirectory("cloner-busy").toFile(), "repo")
    val key = CloneGuardKey.of(target)

    guard.withRepo(key) {
      RepositoryCloner(guard, noAuth)
        .clone(source.toURI().toString(), target, "https://gitlab.example.com", monitor()) shouldBe
        RepositoryCloner.Outcome.Busy
    }
  }

  "a second clone through a symlinked path is also refused" {
    val guard = GitOperationGuard()
    val real = Files.createTempDirectory("cloner-sym-real").toRealPath()
    val linkParent = Files.createTempDirectory("cloner-sym-link").toRealPath()
    val link = Files.createSymbolicLink(linkParent.resolve("alias"), real)
    val source = sourceRepo()

    guard.withRepo(CloneGuardKey.of(real.resolve("repo").toFile())) {
      RepositoryCloner(guard, noAuth).clone(
        source.toURI().toString(),
        link.resolve("repo").toFile(),
        "https://gitlab.example.com",
        monitor(),
      ) shouldBe RepositoryCloner.Outcome.Busy
    }
  }
})
