package com.gitlab.eclipse.ci.joblog

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private fun leftovers(dir: Path): List<String> =
  Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.toList() }

class RawTraceWriterTest : DescribeSpec({
  val dir = tempdir().toPath()

  describe("write") {
    it("writes exactly the bytes the copy produces") {
      val target = dir.resolve("a.log")
      val bytes = ByteArray(300_000) { (it % 251).toByte() }

      RawTraceWriter.write(target) { it.write(bytes) }

      Files.readAllBytes(target).contentEquals(bytes) shouldBe true
    }

    it("replaces an existing file completely") {
      val target = dir.resolve("b.log")
      Files.writeString(target, "old old old old")

      RawTraceWriter.write(target) { it.write("new".toByteArray()) }

      Files.readString(target) shouldBe "new"
    }

    it("leaves no temporary file after a successful write") {
      val own = tempdir().toPath()

      RawTraceWriter.write(own.resolve("c.log")) { it.write(1) }

      leftovers(own) shouldBe listOf("c.log")
    }

    it("removes the temporary file and keeps the old target when the copy fails") {
      val own = tempdir().toPath()
      val target = own.resolve("d.log")
      Files.writeString(target, "keep me")

      shouldThrow<IOException> {
        RawTraceWriter.write(target) {
          it.write("partial".toByteArray())
          throw IOException("disk full")
        }
      }

      Files.readString(target) shouldBe "keep me"
      leftovers(own) shouldBe listOf("d.log")
    }

    it("does not create the target when the copy fails") {
      val own = tempdir().toPath()

      shouldThrow<IllegalStateException> { RawTraceWriter.write(own.resolve("e.log")) { error("cancelled") } }

      leftovers(own) shouldBe emptyList()
    }

    it("fails without the path in the message when the target has no parent directory") {
      val target = Path.of("only-a-name.log")

      val e = shouldThrow<IOException> { RawTraceWriter.write(target) { it.write(1) } }

      e.message shouldBe "no parent directory"
      e.message.orEmpty() shouldNotContain "only-a-name"
    }

    it("fails when the target directory does not exist and creates nothing") {
      val own = tempdir().toPath()

      shouldThrow<IOException> { RawTraceWriter.write(own.resolve("missing").resolve("f.log")) { it.write(1) } }

      leftovers(own) shouldBe emptyList()
    }
  }
})
