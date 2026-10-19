package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.api.BoundedBytes
import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.GitLabConfigurationException
import com.gitlab.eclipse.api.UnstableConnectionException
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.storage.CapturedLog
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.jgit.api.Git
import java.io.File
import java.io.IOException
import java.net.http.HttpTimeoutException
import java.util.concurrent.CancellationException

private val CONN = ConnectionSnapshot("https://gl.example", "tok", "fp", 1L)
private val SIDE = SideRef("src/A.kt", "a".repeat(40))
private const val MAX = 64

class HybridMrFileContentSourceTest : DescribeSpec({
  extension(LoggingKotestExtension)

  val local = mockk<LocalBlobReader>()
  val remote = mockk<RawFileFetcher>()
  val source = HybridMrFileContentSource(local, remote, MAX)

  fun read() = source.read(42L, SIDE, CONN)

  fun remoteThrows(error: Throwable) {
    every { local.read(SIDE, MAX) } returns LocalBlob.Unavailable
    every { remote.fetch(42L, SIDE, MAX, CONN) } throws error
  }

  fun failure(): DiffFailure = read().shouldBeInstanceOf<SideContent.Failed>().kind

  beforeEach { clearMocks(local, remote) }

  describe("local object database first") {
    it("returns local bytes without calling REST") {
      every { local.read(SIDE, MAX) } returns LocalBlob.Found("local".toByteArray())

      read().shouldBeInstanceOf<SideContent.Bytes>().bytes.decodeToString() shouldBe "local"
      verify(exactly = 0) { remote.fetch(any(), any(), any(), any()) }
    }

    it("reports a local blob over the cap as TOO_LARGE without calling REST") {
      every { local.read(SIDE, MAX) } returns LocalBlob.TooLarge

      failure() shouldBe DiffFailure.TOO_LARGE
      verify(exactly = 0) { remote.fetch(any(), any(), any(), any()) }
    }

    it("reports a local gitlink as SUBMODULE without calling REST") {
      every { local.read(SIDE, MAX) } returns LocalBlob.Gitlink

      failure() shouldBe DiffFailure.SUBMODULE
      verify(exactly = 0) { remote.fetch(any(), any(), any(), any()) }
    }
  }

  describe("REST fallback") {
    it("fetches the same side from REST when the commit is not local") {
      every { local.read(SIDE, MAX) } returns LocalBlob.Unavailable
      every { remote.fetch(42L, SIDE, MAX, CONN) } returns BoundedBytes.Ok("remote".toByteArray())

      read().shouldBeInstanceOf<SideContent.Bytes>().bytes.decodeToString() shouldBe "remote"
    }

    it("fetches from REST when the local commit lacks the path") {
      every { local.read(SIDE, MAX) } returns LocalBlob.NotInTree
      every { remote.fetch(42L, SIDE, MAX, CONN) } returns BoundedBytes.Ok("remote".toByteArray())

      read().shouldBeInstanceOf<SideContent.Bytes>().bytes.decodeToString() shouldBe "remote"
    }

    it("reports a REST body over the cap as TOO_LARGE") {
      every { local.read(SIDE, MAX) } returns LocalBlob.Unavailable
      every { remote.fetch(42L, SIDE, MAX, CONN) } returns BoundedBytes.TooLarge

      failure() shouldBe DiffFailure.TOO_LARGE
    }
  }

  describe("REST failure mapping") {
    mapOf(
      401 to DiffFailure.UNAUTHORIZED,
      403 to DiffFailure.NOT_FOUND_OR_DENIED,
      404 to DiffFailure.NOT_FOUND_OR_DENIED,
      429 to DiffFailure.RATE_LIMITED,
      500 to DiffFailure.GENERIC,
    ).forEach { (status, kind) ->
      it("maps HTTP $status to $kind") {
        remoteThrows(GitLabApiException(status, ""))
        failure() shouldBe kind
      }
    }

    it("maps a timeout to TIMED_OUT") {
      remoteThrows(HttpTimeoutException("t"))
      failure() shouldBe DiffFailure.TIMED_OUT
    }

    it("maps an unstable connection to UNSTABLE_CONNECTION") {
      remoteThrows(UnstableConnectionException())
      failure() shouldBe DiffFailure.UNSTABLE_CONNECTION
    }

    it("maps a configuration error to CONFIG") {
      remoteThrows(GitLabConfigurationException("c"))
      failure() shouldBe DiffFailure.CONFIG
    }

    it("maps I/O and other exceptions to GENERIC") {
      remoteThrows(IOException("io"))
      failure() shouldBe DiffFailure.GENERIC
      remoteThrows(IllegalStateException("x"))
      failure() shouldBe DiffFailure.GENERIC
    }

    it("maps an interrupt to GENERIC and keeps the thread's interrupt flag") {
      remoteThrows(InterruptedException())
      try {
        failure() shouldBe DiffFailure.GENERIC
        Thread.currentThread().isInterrupted shouldBe true
      } finally {
        Thread.interrupted()
      }
    }

    it("rethrows cancellation") {
      remoteThrows(CancellationException())
      shouldThrow<CancellationException> { read() }
    }
  }

  describe("a clone that holds the head commit but not the base commit") {
    it("reads head locally and base from REST, never from the working tree") {
      val dir = tempdir()
      val git = Git.init().setDirectory(dir).setInitialBranch("main").call()
      File(dir, "f.txt").writeText("head")
      git.add().addFilepattern("f.txt").call()
      val head = git.commit().setMessage("head").setSign(false).call()
      File(dir, "f.txt").writeText("dirty")
      val fetcher = mockk<RawFileFetcher>()
      val baseSide = SideRef("f.txt", "9".repeat(40))
      val headSide = SideRef("f.txt", head.name)
      every { fetcher.fetch(42L, baseSide, MAX, CONN) } returns BoundedBytes.Ok("base".toByteArray())
      val hybrid = HybridMrFileContentSource(LocalBlobReader { listOf(git.repository.directory) }, fetcher, MAX)

      hybrid.read(42L, baseSide, CONN).shouldBeInstanceOf<SideContent.Bytes>().bytes.decodeToString() shouldBe "base"
      hybrid.read(42L, headSide, CONN).shouldBeInstanceOf<SideContent.Bytes>().bytes.decodeToString() shouldBe "head"
      verify(exactly = 0) { fetcher.fetch(any(), headSide, any(), any()) }
    }
  }

  describe("logging an unexpected REST failure (R4)") {
    it("logs the outcome and the exception class only") {
      val log = CapturedLog().also { it.install() }
      remoteThrows(javax.net.ssl.SSLHandshakeException("PKIX path to https://gl.example/src/A.kt failed"))

      failure() shouldBe DiffFailure.GENERIC

      val line = log.lines.single()
      line shouldContain "outcome=GENERIC"
      line shouldContain "exceptionType=javax.net.ssl.SSLHandshakeException"
      listOf("PKIX", "gl.example", "src/A.kt", SIDE.sha).forEach { line shouldNotContain it }
    }

    it("does not log the failures it maps to a specific kind") {
      val log = CapturedLog().also { it.install() }
      remoteThrows(GitLabApiException(404, ""))

      failure() shouldBe DiffFailure.NOT_FOUND_OR_DENIED
      log.lines.shouldBeEmpty()
    }
  }
})
