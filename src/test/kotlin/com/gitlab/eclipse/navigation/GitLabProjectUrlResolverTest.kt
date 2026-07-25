package com.gitlab.eclipse.navigation

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.eclipse.jgit.api.Git
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.io.File
import java.nio.file.Files

class GitLabProjectUrlResolverTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  fun store(url: String): ScopedPreferenceStore = mockk {
    every { getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns url
  }

  // Creates a temp repo with an origin remote and returns (repoDir, committedFile).
  fun tempRepo(remote: String, fileName: String, commit: Boolean): Pair<File, File> {
    val dir = Files.createTempDirectory("nav-repo").toFile()
    val git = Git.init().setDirectory(dir).call()
    git.repository.config.apply {
      setString("remote", "origin", "url", remote)
      save()
    }
    val f = File(dir, fileName).apply {
      parentFile.mkdirs()
      writeText("hello")
    }
    if (commit) {
      git.add().addFilepattern(".").call()
      git.commit().setMessage("init").setAuthor("t", "t@e").setCommitter("t", "t@e").call()
    }
    git.close()
    return dir to f
  }

  describe("resolveWebUrlForRepo") {
    it("builds the project web URL from the origin remote") {
      val (dir, _) = tempRepo("git@gitlab.com:group/proj.git", "a.txt", commit = true)
      val r = GitLabProjectUrlResolver(store("https://gitlab.com")).resolveWebUrlForRepo(dir)
      r shouldBe GitLabProjectUrlResolver.Resolution.Ok("https://gitlab.com/group/proj")
    }
    it("warns when the remote host does not match the instance") {
      val (dir, _) = tempRepo("git@other.com:group/proj.git", "a.txt", commit = true)
      val r = GitLabProjectUrlResolver(store("https://gitlab.com")).resolveWebUrlForRepo(dir)
      (r is GitLabProjectUrlResolver.Resolution.Warn) shouldBe true
    }
  }

  describe("resolveBlobUrl") {
    it("builds a blob URL with commit SHA, encoded path and line anchor") {
      val (_, file) = tempRepo("git@gitlab.com:group/proj.git", "dir a/b c.txt", commit = true)
      val r = GitLabProjectUrlResolver(store("https://gitlab.com")).resolveBlobUrl(file, 2, 4)
      r as GitLabProjectUrlResolver.Resolution.Ok
      r.url shouldContain "https://gitlab.com/group/proj/-/blob/"
      r.url shouldContain "/dir%20a/b%20c.txt#L3-5"
    }
    it("warns when the file has never been committed") {
      val (_, file) = tempRepo("git@gitlab.com:group/proj.git", "fresh.txt", commit = false)
      val r = GitLabProjectUrlResolver(store("https://gitlab.com")).resolveBlobUrl(file, null, null)
      r shouldBe GitLabProjectUrlResolver.Resolution.Warn(
        "No link exists for the current file. Commit the current file to the repository.",
      )
    }
    it("warns when the file is not inside any repository") {
      val loose = Files.createTempFile("loose", ".txt").toFile()
      val r = GitLabProjectUrlResolver(store("https://gitlab.com")).resolveBlobUrl(loose, null, null)
      r shouldBe GitLabProjectUrlResolver.Resolution.Warn("The current file is not in the project repository.")
    }
  }
})
