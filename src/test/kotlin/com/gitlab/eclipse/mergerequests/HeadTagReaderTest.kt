package com.gitlab.eclipse.mergerequests

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.storage.CapturedLog
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files

class HeadTagReaderTest : DescribeSpec({
  extensions(LoggingKotestExtension)
  val created = mutableListOf<File>()

  fun tempRepo(): Pair<File, Git> {
    val dir = Files.createTempDirectory("tag-repo").toFile()
    created += dir
    return dir to Git.init().setDirectory(dir).call()
  }

  fun Git.commitFile(dir: File, name: String) =
    run {
      File(dir, name).writeText(name)
      add().addFilepattern(".").call()
      commit().setMessage(name).setAuthor("t", "t@e").setCommitter("t", "t@e").setSign(false).call()
    }

  afterSpec { created.forEach { dir -> dir.deleteRecursively() } }

  describe("tagsAt") {
    it("returns nothing when HEAD has no tag") {
      val (dir, git) = tempRepo()
      val sha = git.use { it.commitFile(dir, "a").name }
      HeadTagReader().tagsAt(dir, sha) shouldBe emptyList()
    }

    it("returns the short name of a lightweight tag at HEAD") {
      val (dir, git) = tempRepo()
      val sha = git.use { g -> g.commitFile(dir, "a").also { g.tag().setName("v1").setAnnotated(false).call() }.name }
      HeadTagReader().tagsAt(dir, sha) shouldBe listOf("v1")
    }

    it("returns multiple tags sorted") {
      val (dir, git) = tempRepo()
      val sha = git.use { g ->
        g.commitFile(dir, "a").also {
          g.tag().setName("v2").setAnnotated(false).call()
          g.tag().setName("v1").setAnnotated(false).call()
        }.name
      }
      HeadTagReader().tagsAt(dir, sha) shouldBe listOf("v1", "v2")
    }

    it("peels an annotated tag to its commit") {
      val (dir, git) = tempRepo()
      val sha = git.use { g ->
        g.commitFile(dir, "a").also {
          g.tag().setName("rel").setAnnotated(true).setMessage("m").setSigned(false).call()
        }.name
      }
      HeadTagReader().tagsAt(dir, sha) shouldBe listOf("rel")
    }

    it("ignores tags on other commits") {
      val (dir, git) = tempRepo()
      val sha = git.use { g ->
        g.commitFile(dir, "a")
        g.tag().setName("old").setAnnotated(false).call()
        g.commitFile(dir, "b").name
      }
      HeadTagReader().tagsAt(dir, sha) shouldBe emptyList()
    }

    it("never throws for a missing directory or bogus sha") {
      HeadTagReader().tagsAt(File("/nonexistent/xyz"), "abc") shouldBe emptyList()
      val (dir, git) = tempRepo()
      git.use { it.commitFile(dir, "a") }
      HeadTagReader().tagsAt(dir, "not-a-sha") shouldBe emptyList()
    }

    it("logs a failure as one WARN line with the exception class only") {
      val log = CapturedLog().apply { install() }
      val bogus = File(Files.createTempDirectory("secret-project").toFile(), "missing-repo")
      created += bogus.parentFile

      HeadTagReader().tagsAt(bogus, "abc") shouldBe emptyList()

      log.lines.size shouldBe 1
      log.lines.single() shouldStartWith "Could not read tags at HEAD: "
      log.lines.single() shouldNotContain "secret-project"
    }
  }
})
