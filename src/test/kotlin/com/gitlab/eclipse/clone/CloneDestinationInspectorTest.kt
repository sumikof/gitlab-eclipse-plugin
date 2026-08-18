package com.gitlab.eclipse.clone

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files

private fun repoWithOrigin(dir: File, origin: String): File {
  Git.init().setDirectory(dir).call().use { git ->
    val config = git.repository.config
    config.setString("remote", "origin", "url", origin)
    config.save()
  }
  return dir
}

class CloneDestinationInspectorTest : StringSpec({
  val inspector = CloneDestinationInspector()

  "a missing destination is empty" {
    val root = Files.createTempDirectory("insp-missing").toFile()
    inspector.inspect(File(root, "nope"), "https://h/g/p.git") shouldBe
      CloneDestinationInspector.Verdict.Empty
  }

  "an existing but empty destination is empty" {
    val dir = Files.createTempDirectory("insp-empty").toFile()
    inspector.inspect(dir, "https://h/g/p.git") shouldBe CloneDestinationInspector.Verdict.Empty
  }

  "a repository whose origin matches is an adoption candidate" {
    val dir = Files.createTempDirectory("insp-same").toFile()
    repoWithOrigin(dir, "https://h/g/p.git")
    inspector.inspect(dir, "https://h/g/p.git") shouldBe
      CloneDestinationInspector.Verdict.SameRepository
  }

  "an empty repository with no HEAD is still an adoption candidate" {
    val dir = Files.createTempDirectory("insp-nohead").toFile()
    repoWithOrigin(dir, "https://h/g/p.git")
    dir.resolve(".git").resolve("HEAD").delete()
    inspector.inspect(dir, "https://h/g/p.git") shouldBe
      CloneDestinationInspector.Verdict.SameRepository
  }

  "a repository with a different origin is occupied" {
    val dir = Files.createTempDirectory("insp-other").toFile()
    repoWithOrigin(dir, "https://h/g/other.git")
    inspector.inspect(dir, "https://h/g/p.git") shouldBe
      CloneDestinationInspector.Verdict.Occupied
  }

  "non-git content is occupied" {
    val dir = Files.createTempDirectory("insp-files").toFile()
    File(dir, "notes.txt").writeText("mine")
    inspector.inspect(dir, "https://h/g/p.git") shouldBe
      CloneDestinationInspector.Verdict.Occupied
  }

  "a broken .git is occupied" {
    val dir = Files.createTempDirectory("insp-broken").toFile()
    File(dir, ".git").mkdirs()
    inspector.inspect(dir, "https://h/g/p.git") shouldBe
      CloneDestinationInspector.Verdict.Occupied
  }

  "leftovers are judged by emptiness, not existence" {
    val missing = File(Files.createTempDirectory("insp-lo1").toFile(), "gone")
    val empty = Files.createTempDirectory("insp-lo2").toFile()
    val filled = Files.createTempDirectory("insp-lo3").toFile()
    File(filled, "partial").writeText("x")

    inspector.hasLeftovers(missing) shouldBe false
    inspector.hasLeftovers(empty) shouldBe false
    inspector.hasLeftovers(filled) shouldBe true
  }

  // File.list() returns null for a non-directory, so the emptiness check above would read a plain
  // file at the destination as "nothing there" unless non-directories are handled explicitly.
  "an existing plain file at the destination is occupied" {
    val root = Files.createTempDirectory("insp-file").toFile()
    val destination = File(root, "repo")
    destination.writeText("this is a file, not a directory")
    inspector.inspect(destination, "https://h/g/p.git") shouldBe
      CloneDestinationInspector.Verdict.Occupied
  }

  "a plain file counts as leftovers" {
    val root = Files.createTempDirectory("insp-file-lo").toFile()
    val destination = File(root, "repo")
    destination.writeText("x")
    inspector.hasLeftovers(destination) shouldBe true
  }
})
