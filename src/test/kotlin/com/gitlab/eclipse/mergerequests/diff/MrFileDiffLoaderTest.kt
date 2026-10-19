package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabConfigurationException
import com.gitlab.eclipse.api.UnstableConnectionException
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.storage.CapturedLog
import com.gitlab.eclipse.views.sidebar.ChangeType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.util.concurrent.CancellationException

private val BASE = "a".repeat(40)
private val HEAD = "b".repeat(40)
private const val SECRET_PATH = "secret-dir/Secret.kt"
private const val TOKEN = "glpat-very-secret"

private val SNAPSHOT = ConnectionSnapshot("https://gl.example/", TOKEN, "fp", 1L)

private fun request(
  changeType: ChangeType = ChangeType.MODIFIED,
  oldPath: String? = "src/Old.kt",
  newPath: String? = "src/New.kt",
  aMode: String? = "100644",
  bMode: String? = "100644",
) = MrFileDiffRequest(
  instanceUrl = "https://gl.example",
  authFingerprint = "fp",
  projectId = 42L,
  mrIid = 7L,
  namespaceWithPath = "g/p",
  baseSha = BASE,
  headSha = HEAD,
  oldPath = oldPath,
  newPath = newPath,
  changeType = changeType,
  aMode = aMode,
  bMode = bMode,
)

/** A content source that answers per SHA and records every read. */
private class FakeSource(
  private val answers: Map<String, () -> SideContent> = mapOf(
    BASE to { SideContent.Bytes("base".toByteArray()) },
    HEAD to { SideContent.Bytes("head".toByteArray()) },
  ),
) : MrFileContentSource {
  val reads = mutableListOf<SideRef>()
  val connections = mutableListOf<ConnectionSnapshot>()

  override fun read(projectId: Long, side: SideRef, connection: ConnectionSnapshot): SideContent {
    reads += side
    connections += connection
    return answers.getValue(side.sha)()
  }
}

private fun LoadOutcome.ready(): MrFileDiffContent = shouldBeInstanceOf<LoadOutcome.Ready>().content

private fun LoadOutcome.failure(): DiffFailure = shouldBeInstanceOf<LoadOutcome.Failed>().kind

class MrFileDiffLoaderTest : DescribeSpec({
  extension(LoggingKotestExtension)

  var captures = 0
  var tokenReads = 0

  /** Behaves like `GitLabApiClient.captureConnectionIf`: the credential is read only for an accepted URL. */
  fun capture(snapshot: ConnectionSnapshot = SNAPSHOT): ((String) -> Boolean) -> ConnectionSnapshot? = { accept ->
    captures++
    if (accept(snapshot.instanceUrl)) {
      tokenReads++
      snapshot
    } else {
      null
    }
  }

  beforeEach {
    captures = 0
    tokenReads = 0
  }

  describe("reading the sides") {
    it("CHANGE reads base then head with the captured snapshot and classifies both") {
      val source = FakeSource()

      val content = MrFileDiffLoader(capture(), source).load(request()) { false }.ready()

      source.reads shouldBe listOf(SideRef("src/Old.kt", BASE), SideRef("src/New.kt", HEAD))
      source.connections.forEach { it shouldBeSameInstanceAs SNAPSHOT }
      content.left?.bytes?.decodeToString() shouldBe "base"
      content.right?.bytes?.decodeToString() shouldBe "head"
      content.left?.type shouldBe "kt"
      content.sides shouldBe MrDiffSides.of(request())
    }

    it("NEW reads only the head side") {
      val source = FakeSource()

      val added = request(ChangeType.NEW, oldPath = null, newPath = "n.kt")

      val content = MrFileDiffLoader(capture(), source).load(added) { false }.ready()

      source.reads shouldBe listOf(SideRef("n.kt", HEAD))
      content.left.shouldBeNull()
      content.right?.bytes?.decodeToString() shouldBe "head"
    }

    it("DELETED reads only the base side") {
      val source = FakeSource()

      val deleted = request(ChangeType.DELETED, oldPath = "d.kt", newPath = null)

      val content = MrFileDiffLoader(capture(), source).load(deleted) { false }.ready()

      source.reads shouldBe listOf(SideRef("d.kt", BASE))
      content.right.shouldBeNull()
    }

    it("forces both sides binary when either side looks binary") {
      val source = FakeSource(
        mapOf(
          BASE to { SideContent.Bytes("text".toByteArray()) },
          HEAD to { SideContent.Bytes(byteArrayOf(0x41, 0, 0x42)) },
        ),
      )

      val content = MrFileDiffLoader(capture(), source).load(request()) { false }.ready()

      content.left?.type shouldBe "binary"
      content.right?.type shouldBe "binary"
    }
  }

  describe("connection gate") {
    it("fails CONNECTION_CHANGED without reading when the instance differs") {
      val source = FakeSource()
      val other = SNAPSHOT.copy(instanceUrl = "https://other.example")

      MrFileDiffLoader(capture(other), source).load(request()) { false }.failure() shouldBe
        DiffFailure.CONNECTION_CHANGED
      source.reads.shouldBeEmpty()
    }

    it("never reads the credential of a different instance (R3: gate before any token refresh)") {
      val other = SNAPSHOT.copy(instanceUrl = "https://other.example")

      MrFileDiffLoader(capture(other), FakeSource()).load(request()) { false }

      captures shouldBe 1
      tokenReads shouldBe 0
    }

    it("fails CONNECTION_CHANGED without reading when the account differs") {
      val source = FakeSource()

      MrFileDiffLoader(capture(SNAPSHOT.copy(authFingerprint = "other")), source).load(request()) { false }
        .failure() shouldBe DiffFailure.CONNECTION_CHANGED
      source.reads.shouldBeEmpty()
    }

    it("maps an unstable capture to UNSTABLE_CONNECTION and a configuration error to CONFIG") {
      val source = FakeSource()

      MrFileDiffLoader({ _ -> throw UnstableConnectionException() }, source).load(request()) { false }.failure() shouldBe
        DiffFailure.UNSTABLE_CONNECTION
      MrFileDiffLoader({ _ -> throw GitLabConfigurationException("no url") }, source).load(request()) { false }
        .failure() shouldBe DiffFailure.CONFIG
      source.reads.shouldBeEmpty()
    }
  }

  describe("failures and cancellation") {
    it("submodule fails SUBMODULE before capturing the connection") {
      val source = FakeSource()

      MrFileDiffLoader(capture(), source).load(request(aMode = "160000")) { false }.failure() shouldBe
        DiffFailure.SUBMODULE
      captures shouldBe 0
      source.reads.shouldBeEmpty()
    }

    it("a failed left side fails the whole diff and the right side is never read") {
      val source = FakeSource(mapOf(BASE to { SideContent.Failed(DiffFailure.NOT_FOUND_OR_DENIED) }))

      MrFileDiffLoader(capture(), source).load(request()) { false }.failure() shouldBe DiffFailure.NOT_FOUND_OR_DENIED
      source.reads shouldHaveSize 1
    }

    it("a failed right side fails the whole diff (no partial content)") {
      val source = FakeSource(
        mapOf(
          BASE to { SideContent.Bytes("base".toByteArray()) },
          HEAD to { SideContent.Failed(DiffFailure.TOO_LARGE) },
        ),
      )

      MrFileDiffLoader(capture(), source).load(request()) { false }.failure() shouldBe DiffFailure.TOO_LARGE
    }

    it("cancelled before reading → Cancelled, nothing read") {
      val source = FakeSource()

      MrFileDiffLoader(capture(), source).load(request()) { true } shouldBeSameInstanceAs LoadOutcome.Cancelled
      source.reads.shouldBeEmpty()
    }

    it("cancelled after the left side → Cancelled and the right side is not read") {
      val source = FakeSource()

      val outcome = MrFileDiffLoader(capture(), source).load(request()) { source.reads.size >= 1 }

      outcome shouldBeSameInstanceAs LoadOutcome.Cancelled
      source.reads shouldBe listOf(SideRef("src/Old.kt", BASE))
    }

    it("cancelled after the right side → Cancelled") {
      val source = FakeSource()

      MrFileDiffLoader(capture(), source).load(request()) { source.reads.size >= 2 } shouldBeSameInstanceAs
        LoadOutcome.Cancelled
    }

    it("rethrows CancellationException") {
      val source = FakeSource(mapOf(BASE to { throw CancellationException() }))

      shouldThrow<CancellationException> { MrFileDiffLoader(capture(), source).load(request()) { false } }
    }

    it("folds an unexpected exception into GENERIC and logs only the outcome and exception type") {
      val log = CapturedLog().also { it.install() }
      val marker = "boom-$SECRET_PATH-$TOKEN"
      val source = FakeSource(mapOf(BASE to { throw IllegalStateException(marker) }))
      val secretRequest = request(oldPath = SECRET_PATH, newPath = SECRET_PATH)

      MrFileDiffLoader(capture(), source).load(secretRequest) { false }.failure() shouldBe DiffFailure.GENERIC

      log.lines shouldHaveSize 1
      val line = log.lines.single()
      line shouldContain "outcome=GENERIC"
      line shouldContain "exceptionType=java.lang.IllegalStateException"
      val secrets = listOf(marker, "boom", SECRET_PATH, "Secret", BASE, HEAD, "gl.example", TOKEN)
      secrets.forEach { line shouldNotContain it }
    }
  }
})
