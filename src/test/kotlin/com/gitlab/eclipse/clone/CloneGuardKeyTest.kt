package com.gitlab.eclipse.clone

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

class CloneGuardKeyTest : StringSpec({
  "resolves the nearest existing ancestor and lowercases the whole key" {
    val root = Files.createTempDirectory("clone-key").toRealPath()
    val target = root.resolve("Foo").toFile()

    val key = CloneGuardKey.of(target)

    key shouldBe (root.toString() + java.io.File.separator + "foo").lowercase()
    key shouldNotContain "Foo"
  }

  "a symlinked parent and the real parent produce the same key" {
    val real = Files.createTempDirectory("clone-key-real").toRealPath()
    val linkParent = Files.createTempDirectory("clone-key-link").toRealPath()
    val link = Files.createSymbolicLink(linkParent.resolve("alias"), real)

    CloneGuardKey.of(link.resolve("repo").toFile()) shouldBe
      CloneGuardKey.of(real.resolve("repo").toFile())
  }

  "case differences in the target name collapse to one key" {
    val root = Files.createTempDirectory("clone-key-case").toRealPath()

    CloneGuardKey.of(root.resolve("Repo").toFile()) shouldBe
      CloneGuardKey.of(root.resolve("repo").toFile())
  }

  "the key does not change when the target is created between two calls" {
    val root = Files.createTempDirectory("clone-key-race").toRealPath()
    val target = root.resolve("Foo").toFile()

    val before = CloneGuardKey.of(target)
    Files.createDirectory(target.toPath()) // now empty: a second run still passes the entry check
    val after = CloneGuardKey.of(target)

    after shouldBe before
  }

  "several missing segments are all kept" {
    val root = Files.createTempDirectory("clone-key-deep").toRealPath()

    CloneGuardKey.of(root.resolve("A").resolve("B").toFile()) shouldBe
      (root.toString() + java.io.File.separator + "a" + java.io.File.separator + "b").lowercase()
  }
})
