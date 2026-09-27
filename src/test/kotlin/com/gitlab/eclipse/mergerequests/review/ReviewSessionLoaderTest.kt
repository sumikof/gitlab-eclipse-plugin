package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.DiscussionsReadResult
import com.gitlab.eclipse.api.GraphQlException
import com.gitlab.eclipse.api.TruncationReason
import com.gitlab.eclipse.api.UnstableConnectionException
import com.gitlab.eclipse.api.model.GitLabDiffRefs
import com.gitlab.eclipse.api.model.GitLabDiscussion
import com.gitlab.eclipse.api.model.GitLabMrVersion
import com.gitlab.eclipse.api.model.GitLabNote
import com.gitlab.eclipse.api.model.GitLabNotePermissions
import com.gitlab.eclipse.api.model.GitLabNotePosition
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.api.Git
import java.io.File
import java.io.IOException
import java.time.Duration

private const val HEAD = "head1"
private const val PATH = "src/Foo.kt"

private val CONN = ConnectionSnapshot(
  instanceUrl = "https://gitlab.example.com",
  token = "secret-token",
  authFingerprint = "fp-1",
  configGeneration = 1L,
)

private val MR_REF = MergeRequestRef(
  instanceUrl = "https://gitlab.example.com/",
  authFingerprint = "fp-1",
  projectId = 7L,
  mrIid = 42L,
  mrGid = "gid://gitlab/MergeRequest/900",
  namespaceWithPath = "group/project",
)

private const val ONE_HUNK = "@@ -1,2 +1,3 @@\n a\n+b\n c\n"

private fun diffEntry(
  newPath: String = PATH,
  oldPath: String = newPath,
  diff: String? = ONE_HUNK,
  renamedFile: Boolean = false,
  tooLarge: Boolean? = null,
) = GitLabMrVersion.Diff(
  oldPath = oldPath,
  newPath = newPath,
  renamedFile = renamedFile,
  diff = diff,
  tooLarge = tooLarge,
)

private fun mrVersion(
  head: String? = HEAD,
  base: String? = "base1",
  start: String? = "start1",
  diffs: List<GitLabMrVersion.Diff> = listOf(diffEntry()),
) = GitLabMrVersion(id = 5L, headCommitSha = head, baseCommitSha = base, startCommitSha = start, diffs = diffs)

private val PERMS = GitLabNotePermissions(resolveNote = true, adminNote = false, createNote = true)

private fun placedDiscussion(line: Int) = GitLabDiscussion(
  replyId = "disc-$line",
  createdAt = "2026-01-01T00:00:00Z",
  resolved = false,
  resolvable = true,
  notes = listOf(
    GitLabNote(
      id = "note-$line",
      createdAt = "2026-01-01T00:00:00Z",
      system = false,
      authorUsername = "alice",
      body = "a comment",
      permissions = PERMS,
      position = GitLabNotePosition(
        positionType = "text",
        newPath = PATH,
        oldPath = PATH,
        newLine = line,
        oldLine = null,
        diffRefs = GitLabDiffRefs(baseSha = "base1", headSha = HEAD, startSha = "start1"),
      ),
    ),
  ),
  hasMoreNotes = false,
)

/** Records every call so the tests can assert what the loader passed and how often. */
private class Fakes {
  var captures = 0
  var captured: () -> ConnectionSnapshot = { CONN }
  val versionCalls = mutableListOf<Triple<String, Long, ConnectionSnapshot>>()
  var version: () -> GitLabMrVersion? = { mrVersion() }
  val discussionCalls = mutableListOf<List<Any>>()
  var discussions: () -> DiscussionsReadResult = {
    DiscussionsReadResult(canCreateNote = true, discussions = listOf(placedDiscussion(2)), truncation = null)
  }
  val blobCalls = mutableListOf<List<String>>()
  var blobIdsEqual: Boolean? = null

  val blobs = object : DiffBlobs {
    override fun headBlobIsEmpty(headSha: String, newPath: String): Boolean {
      blobCalls += listOf("empty", headSha, newPath)
      return false
    }

    override fun blobIdsEqual(baseSha: String, oldPath: String, headSha: String, newPath: String): Boolean? {
      blobCalls += listOf("equal", baseSha, oldPath, headSha, newPath)
      return blobIdsEqual
    }
  }

  fun loader() = ReviewSessionLoader(
    captureConnection = {
      captures++
      captured()
    },
    getLatestMrVersion = { id, iid, conn ->
      versionCalls += Triple(id, iid, conn)
      version()
    },
    getDiscussions = { conn, ns, iid, deadline ->
      discussionCalls += listOf(conn, ns, iid, deadline)
      discussions()
    },
    blobs = blobs,
    deadline = Duration.ofSeconds(5),
  )
}

class ReviewSessionLoaderTest : DescribeSpec({
  describe("load (design §9.1 BG)") {
    it("captures the connection once and passes that same snapshot to every request") {
      val f = Fakes()

      val result = f.loader().load(MR_REF, PATH, HEAD, conn = null)

      result.shouldBeInstanceOf<LoadResult.Loaded>()
      f.captures shouldBe 1
      f.versionCalls.single().third shouldBeSameInstanceAs CONN
      f.discussionCalls.single()[0] shouldBeSameInstanceAs CONN
    }

    it("uses a connection handed in by the caller without capturing another") {
      val f = Fakes()
      val given = CONN.copy(configGeneration = 9L)

      f.loader().load(MR_REF, PATH, HEAD, conn = given).shouldBeInstanceOf<LoadResult.Loaded>()

      f.captures shouldBe 0
      f.versionCalls.single().third shouldBeSameInstanceAs given
      f.discussionCalls.single()[0] shouldBeSameInstanceAs given
    }

    it("builds the snapshot from the version, the diff entry and the discussions") {
      val f = Fakes()

      val loaded = f.loader().load(MR_REF, PATH, HEAD, conn = null).shouldBeInstanceOf<LoadResult.Loaded>()

      f.versionCalls.single().first shouldBe "7"
      f.versionCalls.single().second shouldBe 42L
      f.discussionCalls.single().drop(1) shouldBe listOf("group/project", 42L, Duration.ofSeconds(5))
      val s = loaded.snapshot
      s.identity shouldBe SessionIdentity("https://gitlab.example.com/", "fp-1", 7L, 42L, HEAD, PATH)
      s.mrRef shouldBe MR_REF
      listOf(s.baseSha, s.startSha, s.headSha) shouldBe listOf("base1", "start1", HEAD)
      listOf(s.oldPath, s.newPath) shouldBe listOf(PATH, PATH)
      s.lineMap.classify(2) shouldBe NewLineKind.Added
      s.lineMap.classify(3) shouldBe NewLineKind.Unchanged(2)
      s.canCreateNote shouldBe true
      s.placements.map { it.oneBasedLine } shouldBe listOf(2)
      loaded.complete shouldBe true
    }

    it("reports a partial discussions fetch as incomplete (§9.1.1)") {
      val f = Fakes()
      f.discussions = {
        DiscussionsReadResult(canCreateNote = false, discussions = emptyList(), truncation = TruncationReason.DEADLINE)
      }

      val loaded = f.loader().load(MR_REF, PATH, HEAD, conn = null).shouldBeInstanceOf<LoadResult.Loaded>()

      loaded.complete shouldBe false
      loaded.snapshot.canCreateNote shouldBe false
    }

    it("matches the diff entry by its new path, keeping the old path of a rename") {
      val f = Fakes()
      f.version = { mrVersion(diffs = listOf(diffEntry(newPath = "src/Other.kt"), diffEntry(oldPath = "src/Old.kt"))) }

      val loaded = f.loader().load(MR_REF, PATH, HEAD, conn = null).shouldBeInstanceOf<LoadResult.Loaded>()

      loaded.snapshot.oldPath shouldBe "src/Old.kt"
      loaded.snapshot.newPath shouldBe PATH
    }

    it("classifies an empty rename diff as Identity when the local blobs are equal (§12.2.1)") {
      val f = Fakes()
      f.blobIdsEqual = true
      f.version = { mrVersion(diffs = listOf(diffEntry(oldPath = "src/Old.kt", diff = "", renamedFile = true))) }

      val loaded = f.loader().load(MR_REF, PATH, HEAD, conn = null).shouldBeInstanceOf<LoadResult.Loaded>()

      loaded.snapshot.lineMap shouldBe DiffLineMap.Identity
      f.blobCalls shouldBe listOf(listOf("equal", "base1", "src/Old.kt", HEAD, PATH))
    }

    it("keeps a too-large diff as a session with no line map") {
      val f = Fakes()
      f.version = { mrVersion(diffs = listOf(diffEntry(diff = "", tooLarge = true))) }

      val loaded = f.loader().load(MR_REF, PATH, HEAD, conn = null).shouldBeInstanceOf<LoadResult.Loaded>()

      loaded.snapshot.lineMap shouldBe DiffLineMap.Unavailable
      f.blobCalls shouldBe emptyList()
    }

    describe("refusals, each before any later request") {
      it("refuses when the connection's instance changed since the MR was found") {
        val f = Fakes()
        f.captured = { CONN.copy(instanceUrl = "https://other.example.com") }

        f.loader().load(MR_REF, PATH, HEAD, conn = null) shouldBe
          LoadResult.Refused(ReviewSessionLoader.CONNECTION_CHANGED_MESSAGE)

        f.versionCalls shouldBe emptyList()
      }

      it("refuses when the connection's account changed since the MR was found") {
        val f = Fakes()

        f.loader().load(MR_REF, PATH, HEAD, conn = CONN.copy(authFingerprint = "fp-2"))
          .shouldBeInstanceOf<LoadResult.Refused>()

        f.versionCalls shouldBe emptyList()
      }

      it("refuses when the connection cannot be captured") {
        val f = Fakes()
        f.captured = { throw UnstableConnectionException() }

        val refused = f.loader().load(MR_REF, PATH, HEAD, conn = null).shouldBeInstanceOf<LoadResult.Refused>()

        refused shouldBe LoadResult.Refused(ReviewSessionLoader.CONNECTION_UNSTABLE_MESSAGE, cause = null)
        f.versionCalls shouldBe emptyList()
      }

      it("refuses when the MR has no version") {
        val f = Fakes()
        f.version = { null }

        f.loader().load(MR_REF, PATH, HEAD, conn = null).shouldBeInstanceOf<LoadResult.Refused>()

        f.discussionCalls shouldBe emptyList()
      }

      it("refuses when the latest version's head is not the expected one (pushed after opening)") {
        val f = Fakes()
        f.version = { mrVersion(head = "head2") }

        f.loader().load(MR_REF, PATH, HEAD, conn = null) shouldBe LoadResult.Refused(VersionDiff.Missing.HEAD_MOVED.message)

        f.discussionCalls shouldBe emptyList()
      }

      it("refuses when the version lacks its base or start sha") {
        val f = Fakes()
        f.version = { mrVersion(start = null) }

        f.loader().load(MR_REF, PATH, HEAD, conn = null).shouldBeInstanceOf<LoadResult.Refused>()

        f.discussionCalls shouldBe emptyList()
      }

      it("refuses when no diff entry has the file as its new path") {
        val f = Fakes()
        f.version = { mrVersion(diffs = listOf(diffEntry(newPath = "src/Other.kt", oldPath = PATH))) }

        f.loader().load(MR_REF, PATH, HEAD, conn = null).shouldBeInstanceOf<LoadResult.Refused>()

        f.discussionCalls shouldBe emptyList()
      }

      it("refuses, rather than throwing, when a request fails") {
        val f = Fakes()
        val boom = GraphQlException(hasDataKey = false, messages = listOf("boom"))
        f.discussions = { throw boom }

        val refused = f.loader().load(MR_REF, PATH, HEAD, conn = null).shouldBeInstanceOf<LoadResult.Refused>()

        refused.cause shouldBeSameInstanceAs boom
      }

      it("uses a fixed message, hands the unexpected cause back for logging, and keeps its text out of toString") {
        val f = Fakes()
        val cause = IOException("server said secret-token")
        f.version = { throw cause }

        val refused = f.loader().load(MR_REF, PATH, HEAD, conn = null).shouldBeInstanceOf<LoadResult.Refused>()

        refused.reason shouldBe ReviewSessionLoader.LOAD_FAILED_MESSAGE
        refused.cause shouldBeSameInstanceAs cause
        refused.toString() shouldNotContain "secret-token"
      }
    }

    it("rethrows cancellation") {
      val f = Fakes()
      f.version = { throw CancellationException("cancelled") }

      shouldThrow<CancellationException> { f.loader().load(MR_REF, PATH, HEAD, conn = null) }
    }
  }

  describe("RepositoryDiffBlobs (§12.2.1)") {
    lateinit var dir: File
    lateinit var git: Git

    beforeEach {
      dir = tempdir()
      git = Git.init().setDirectory(dir).setInitialBranch("main").call()
    }

    afterEach { git.close() }

    fun commit(): String {
      git.add().addFilepattern(".").call()
      git.add().addFilepattern(".").setUpdate(true).call()
      return git.commit().setMessage("m").setAuthor("t", "t@example.com").setSign(false).call().name
    }

    fun write(name: String, text: String) = File(dir, name).apply {
      parentFile.mkdirs()
      writeText(text)
    }

    it("confirms a pure rename and rejects a rename that changed the content") {
      write("a/Old.kt", "x\ny\n")
      val base = commit()
      File(dir, "a/Old.kt").renameTo(File(dir, "a/New.kt"))
      write("a/Changed.kt", "x\ny\n")
      val head = commit()
      write("a/Changed.kt", "x\nz\n")
      val edited = commit()
      val blobs = RepositoryDiffBlobs(git.repository)

      blobs.blobIdsEqual(base, "a/Old.kt", head, "a/New.kt") shouldBe true
      blobs.blobIdsEqual(base, "a/Old.kt", edited, "a/Changed.kt") shouldBe false
      blobs.blobIdsEqual(base, "a/Missing.kt", head, "a/New.kt") shouldBe false
    }

    it("cannot decide when the base commit is not available locally") {
      write("f.kt", "x\n")
      val head = commit()

      RepositoryDiffBlobs(git.repository)
        .blobIdsEqual("0123456789012345678901234567890123456789", "f.kt", head, "f.kt").shouldBeNull()
      RepositoryDiffBlobs(git.repository).blobIdsEqual("not-a-sha", "f.kt", head, "f.kt").shouldBeNull()
    }

    it("tells an empty HEAD blob from a non-empty or missing one") {
      write("empty.kt", "")
      write("full.kt", "x\n")
      val head = commit()
      val blobs = RepositoryDiffBlobs(git.repository)

      blobs.headBlobIsEmpty(head, "empty.kt") shouldBe true
      blobs.headBlobIsEmpty(head, "full.kt") shouldBe false
      blobs.headBlobIsEmpty(head, "missing.kt") shouldBe false
      blobs.headBlobIsEmpty("0123456789012345678901234567890123456789", "empty.kt") shouldBe false
    }

    it("WorkspaceDiffBlobs answers from the repository that has the head commit") {
      write("empty.kt", "")
      val head = commit()
      val unrelated = tempdir().also { Git.init().setDirectory(it).call().close() }

      val blobs = WorkspaceDiffBlobs(gitDirs = { listOf(File(unrelated, ".git"), File(dir, ".git")) })

      blobs.headBlobIsEmpty(head, "empty.kt") shouldBe true
      blobs.blobIdsEqual(head, "empty.kt", head, "empty.kt") shouldBe true
      WorkspaceDiffBlobs(gitDirs = { listOf(File(unrelated, ".git")) })
        .blobIdsEqual(head, "empty.kt", head, "empty.kt").shouldBeNull()
    }
  }
})
