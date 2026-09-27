package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files

/** Real repositories: each case commits a file, then shapes the working tree / index around it. */
class BodyIdentityGateTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val utf8 = Charsets.UTF_8

  lateinit var dir: File
  lateinit var git: Git

  beforeEach {
    dir = tempdir()
    git = Git.init().setDirectory(dir).setInitialBranch("main").call()
  }

  afterEach { git.close() }

  fun commitAll(vararg paths: String) {
    paths.forEach { git.add().addFilepattern(it).call() }
    git.commit().setMessage("m").setAuthor("t", "t@example.com").setSign(false).call()
  }

  fun writeAndCommit(name: String, bytes: ByteArray): File {
    val file = File(dir, name)
    file.writeBytes(bytes)
    commitAll(name)
    return file
  }

  fun check(path: String, text: String, charset: Charset = utf8, file: File = File(dir, path)) =
    checkBodyIdentity(git.repository, path, file, text, charset)

  fun headBlob(path: String): ByteArray {
    val repo = git.repository
    val tree = RevWalk(repo).use { it.parseCommit(repo.resolve("HEAD")).tree }
    return TreeWalk.forPath(repo, path, tree).use { repo.open(it.getObjectId(0)).bytes }
  }

  fun different(reason: BodyMismatch) = BodyIdentity.Different(reason)

  fun canCreateSymlink(): Boolean {
    val probe = tempdir()
    return try {
      Files.createSymbolicLink(File(probe, "link").toPath(), File("target").toPath())
      true
    } catch (_: UnsupportedOperationException) {
      false
    } catch (_: IOException) {
      false
    }
  }

  fun runGit(vararg args: String): Int =
    ProcessBuilder(listOf("git", *args)).directory(dir).redirectErrorStream(true).start()
      .also { it.inputStream.readBytes() }.waitFor()

  fun gitCliAvailable(): Boolean = try {
    ProcessBuilder("git", "--version").redirectErrorStream(true).start()
      .also { it.inputStream.readBytes() }.waitFor() == 0
  } catch (_: IOException) {
    false
  }

  describe("an untouched committed file") {
    it("is the same for plain UTF-8 LF text ending with a newline") {
      writeAndCommit("a.txt", "first\nsecond\n".toByteArray(utf8))

      check("a.txt", "first\nsecond\n") shouldBe BodyIdentity.Same
    }

    it("is the same for text without a trailing newline") {
      writeAndCommit("a.txt", "first\nsecond".toByteArray(utf8))

      check("a.txt", "first\nsecond") shouldBe BodyIdentity.Same
    }

    it("is the same for an executable file") {
      val file = writeAndCommit("run.sh", "echo 1\n".toByteArray(utf8))
      file.setExecutable(true)
      commitAll("run.sh")

      check("run.sh", "echo 1\n") shouldBe BodyIdentity.Same
    }

    it("is the same for a CRLF working tree under core.autocrlf=true") {
      git.repository.config.apply { setBoolean("core", null, "autocrlf", true) }.save()
      writeAndCommit("a.txt", "first\r\nsecond\r\n".toByteArray(utf8))

      check("a.txt", "first\r\nsecond\r\n") shouldBe BodyIdentity.Same
      headBlob("a.txt").contains('\r'.code.toByte()) shouldBe false
    }

    it("is the same for Shift_JIS text decoded with Shift_JIS") {
      val sjis = Charset.forName("Shift_JIS")
      writeAndCommit("j.txt", "日本語\nテキスト\n".toByteArray(sjis))

      check("j.txt", "日本語\nテキスト\n", sjis) shouldBe BodyIdentity.Same
    }

    it("differs for Shift_JIS bytes decoded as UTF-8") {
      val sjis = Charset.forName("Shift_JIS")
      writeAndCommit("j.txt", "日本語\nテキスト\n".toByteArray(sjis))

      check("j.txt", "日本語\nテキスト\n", utf8) shouldBe different(BodyMismatch.DISK_TEXT_DIFFERS)
    }

    it("is the same for a UTF-8 BOM file whose document text has no BOM") {
      writeAndCommit("b.txt", "\uFEFFfirst\nsecond\n".toByteArray(utf8))

      check("b.txt", "first\nsecond\n") shouldBe BodyIdentity.Same
    }

    it("strips only one leading BOM") {
      writeAndCommit("b.txt", "\uFEFF\uFEFFfirst\n".toByteArray(utf8))

      check("b.txt", "first\n") shouldBe different(BodyMismatch.DISK_TEXT_DIFFERS)
      check("b.txt", "\uFEFFfirst\n") shouldBe BodyIdentity.Same
    }
  }

  describe("G8a attributes") {
    it("rejects a path with a filter attribute") {
      writeAndCommit("a.txt", "first\n".toByteArray(utf8))
      writeAndCommit(".gitattributes", "a.txt filter=lfs\n".toByteArray(utf8))

      check("a.txt", "first\n") shouldBe different(BodyMismatch.FILTER_ATTRIBUTE)
    }

    it("rejects a path with a working-tree-encoding attribute") {
      writeAndCommit("a.txt", "first\n".toByteArray(utf8))
      writeAndCommit(".gitattributes", "*.txt working-tree-encoding=UTF-16\n".toByteArray(utf8))

      check("a.txt", "first\n") shouldBe different(BodyMismatch.FILTER_ATTRIBUTE)
    }

    it("accepts a path whose filter attribute is explicitly unset") {
      writeAndCommit("a.txt", "first\n".toByteArray(utf8))
      writeAndCommit(".gitattributes", "a.txt -filter\n".toByteArray(utf8))

      check("a.txt", "first\n") shouldBe BodyIdentity.Same
    }
  }

  describe("G8a'' file mode") {
    it("rejects a symbolic link to a file inside the repository").config(enabledIf = { canCreateSymlink() }) {
      writeAndCommit("target.txt", "first\n".toByteArray(utf8))
      Files.createSymbolicLink(File(dir, "link.txt").toPath(), File("target.txt").toPath())
      commitAll("link.txt")

      check("link.txt", "first\n") shouldBe different(BodyMismatch.NOT_REGULAR_FILE)
    }

    it("rejects a path that is not in the index") {
      writeAndCommit("a.txt", "first\n".toByteArray(utf8))
      File(dir, "new.txt").writeText("new\n")

      check("new.txt", "new\n") shouldBe different(BodyMismatch.NOT_REGULAR_FILE)
    }

    it("rejects a staged new file that is not in HEAD") {
      writeAndCommit("a.txt", "first\n".toByteArray(utf8))
      File(dir, "new.txt").writeText("new\n")
      git.add().addFilepattern("new.txt").call()

      check("new.txt", "new\n") shouldBe different(BodyMismatch.NOT_REGULAR_FILE)
    }
  }

  describe("file and path consistency") {
    it("rejects a file outside the work tree even when its text matches a clean tracked file") {
      writeAndCommit("a.txt", "first\n".toByteArray(utf8))
      val outside = File(tempdir(), "a.txt").apply { writeText("first\n") }

      check("a.txt", "first\n", file = outside) shouldBe different(BodyMismatch.UNREADABLE)
    }
  }

  describe("G8a' index flags") {
    fun flagAndEdit(set: (DirCacheEntry) -> Unit) {
      writeAndCommit("a.txt", "first\n".toByteArray(utf8))
      val cache = git.repository.lockDirCache()
      set(cache.getEntry("a.txt"))
      cache.write()
      cache.commit()
      File(dir, "a.txt").writeText("edited\n")
    }

    it("rejects an assume-valid entry edited in the working tree") {
      flagAndEdit { it.isAssumeValid = true }

      check("a.txt", "edited\n") shouldBe different(BodyMismatch.INDEX_FLAG)
    }

    // JGit has no setter for skip-worktree, so the flag is set with the git CLI.
    it("rejects a skip-worktree entry edited in the working tree").config(enabledIf = { gitCliAvailable() }) {
      writeAndCommit("a.txt", "first\n".toByteArray(utf8))
      runGit("update-index", "--skip-worktree", "a.txt") shouldBe 0
      git.repository.readDirCache().getEntry("a.txt").isSkipWorkTree shouldBe true
      File(dir, "a.txt").writeText("edited\n")

      check("a.txt", "edited\n") shouldBe different(BodyMismatch.INDEX_FLAG)
    }
  }

  describe("G8b disk text") {
    it("rejects document text that differs from the working tree (editor-only edit)") {
      writeAndCommit("a.txt", "first\n".toByteArray(utf8))

      check("a.txt", "first\nunsaved\n") shouldBe different(BodyMismatch.DISK_TEXT_DIFFERS)
    }

    it("does not normalize newlines") {
      writeAndCommit("a.txt", "first\nsecond\n".toByteArray(utf8))

      check("a.txt", "first\r\nsecond\r\n") shouldBe different(BodyMismatch.DISK_TEXT_DIFFERS)
    }

    it("rejects a file rewritten between the first read and the re-read") {
      val file = writeAndCommit("a.txt", "first\n".toByteArray(utf8))

      val result = checkBodyIdentity(git.repository, "a.txt", file, "first\n", utf8) {
        file.writeText("rewritten\n")
      }

      result shouldBe different(BodyMismatch.DISK_TEXT_DIFFERS)
    }

    it("reports an unreadable file") {
      writeAndCommit("a.txt", "first\n".toByteArray(utf8))
      File(dir, "a.txt").delete()

      check("a.txt", "first\n") shouldBe different(BodyMismatch.UNREADABLE)
    }
  }

  describe("G8b line delimiters") {
    it("rejects a lone CR at the end of a line mid-file") {
      writeAndCommit("a.txt", "first\rsecond\nthird\n".toByteArray(utf8))

      check("a.txt", "first\rsecond\nthird\n") shouldBe different(BodyMismatch.UNSUPPORTED_LINE_DELIMITER)
    }

    it("rejects a lone CR as the last character") {
      writeAndCommit("a.txt", "first\nsecond\r".toByteArray(utf8))

      check("a.txt", "first\nsecond\r") shouldBe different(BodyMismatch.UNSUPPORTED_LINE_DELIMITER)
    }

    it("still accepts CRLF committed as-is") {
      writeAndCommit("a.txt", "first\r\nsecond\r\n".toByteArray(utf8))

      check("a.txt", "first\r\nsecond\r\n") shouldBe BodyIdentity.Same
    }
  }

  describe("G8c status") {
    it("rejects a staged edit whose working tree equals the index") {
      writeAndCommit("a.txt", "first\n".toByteArray(utf8))
      File(dir, "a.txt").writeText("edited\n")
      git.add().addFilepattern("a.txt").call()

      check("a.txt", "edited\n") shouldBe different(BodyMismatch.STATUS_NOT_CLEAN)
    }

    it("rejects an unstaged edit that the document text matches") {
      writeAndCommit("a.txt", "first\n".toByteArray(utf8))
      File(dir, "a.txt").writeText("edited\n")

      check("a.txt", "edited\n") shouldBe different(BodyMismatch.STATUS_NOT_CLEAN)
    }
  }
})
