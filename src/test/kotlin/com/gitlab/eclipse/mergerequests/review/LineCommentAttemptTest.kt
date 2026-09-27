package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.DiscussionService
import com.gitlab.eclipse.api.DiscussionsReadResult
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.GraphQlException
import com.gitlab.eclipse.api.TruncationReason
import com.gitlab.eclipse.api.UnstableConnectionException
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabMrVersion
import com.gitlab.eclipse.mergerequests.CurrentBranch
import com.gitlab.eclipse.mergerequests.RepositoryContext
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.io.IOException

private const val HEAD = "head1"
private const val PATH = "src/Foo.kt"
private const val BODY = "Please rename this."
private const val TEXT = "a\nb\nc\n"

private val CONN = ConnectionSnapshot(
  instanceUrl = "https://gitlab.example.com",
  token = "secret-token",
  authFingerprint = "fp-1",
  configGeneration = 1L,
)

/** Line 1 unchanged (old 1), line 2 added, line 3 unchanged (old 2). */
private const val ONE_HUNK = "@@ -1,2 +1,3 @@\n a\n+b\n c\n"

private fun entry(
  newPath: String = PATH,
  oldPath: String = newPath,
  diff: String? = ONE_HUNK,
  tooLarge: Boolean? = null,
) = GitLabMrVersion.Diff(oldPath = oldPath, newPath = newPath, diff = diff, tooLarge = tooLarge)

private fun mrVersion(head: String? = HEAD, diffs: List<GitLabMrVersion.Diff> = listOf(entry())) =
  GitLabMrVersion(id = 5L, headCommitSha = head, baseCommitSha = "base1", startCommitSha = "start1", diffs = diffs)

private fun mergeRequest(sha: String? = HEAD, references: String? = "group/project!42") = GitLabMergeRequest(
  id = 900L,
  iid = 42L,
  title = "t",
  projectId = 7L,
  webUrl = "https://gitlab.example.com/group/project/-/merge_requests/42",
  state = "opened",
  sha = sha,
  references = references?.let { GitLabMergeRequest.Reference(it) },
)

private val EXPECTED_REF = MergeRequestRef(
  instanceUrl = CONN.instanceUrl,
  authFingerprint = CONN.authFingerprint,
  projectId = 7L,
  mrIid = 42L,
  mrGid = "gid://gitlab/MergeRequest/900",
  namespaceWithPath = "group/project",
)

private val EXPECTED_IDENTITY = SessionIdentity(CONN.instanceUrl, CONN.authFingerprint, 7L, 42L, HEAD, PATH)

private fun sessionSnapshot(identity: SessionIdentity = EXPECTED_IDENTITY) = ReviewSessionSnapshot(
  identity = identity,
  mrRef = EXPECTED_REF.copy(namespaceWithPath = "session/ns"),
  baseSha = "base1",
  startSha = "start1",
  headSha = identity.headSha,
  oldPath = identity.newPath,
  newPath = identity.newPath,
  lineMap = DiffLineMap.Unavailable,
  canCreateNote = true,
  placements = emptyList(),
)

private fun position(newLine: Int, oldLine: Int? = null, oldPath: String = PATH): Map<String, Any?> = buildMap {
  put("baseSha", "base1")
  put("headSha", HEAD)
  put("startSha", "start1")
  put("paths", mapOf("oldPath" to oldPath, "newPath" to PATH))
  put("newLine", newLine)
  if (oldLine != null) put("oldLine", oldLine)
}

private data class Sent(
  val connection: ConnectionSnapshot,
  val mrGid: String,
  val body: String,
  val position: Map<String, Any?>,
)

/** A work tree on disk plus fakes for every effect; each call is recorded. */
private class Harness(val workTree: File) {
  val file = File(workTree, PATH).apply {
    parentFile.mkdirs()
    writeText(TEXT)
  }
  var contexts = listOf(context(workTree))
  var headSha: String? = HEAD
  var mr: GitLabMergeRequest? = mergeRequest()
  var canCreate = true
  var body: BodyIdentity = BodyIdentity.Same
  var version: () -> GitLabMrVersion? = { mrVersion() }
  var captured: () -> ConnectionSnapshot = { CONN }
  var pinned: ConnectionSnapshot = CONN
  var registryActive = true
  var registryEpoch = 0L
  var send: (Sent) -> Unit = {}

  var captures = 0
  var contextReads = 0
  val branchReads = mutableListOf<File>()
  val lookups = mutableListOf<ConnectionSnapshot>()
  val permissionChecks = mutableListOf<List<Any>>()
  val versionCalls = mutableListOf<List<Any>>()
  val bodyChecks = mutableListOf<List<Any>>()
  val sent = mutableListOf<Sent>()

  val repository = mockk<Repository>(relaxed = true)
  val apiClient = mockk<GitLabApiClient>().also { client ->
    every { client.captureConnectionIf(any()) } answers {
      val accept = firstArg<(String) -> Boolean>()
      pinned.takeIf { snapshot -> accept(snapshot.instanceUrl) }
    }
  }

  fun context(root: File) = RepositoryContext(
    gitDir = File(root, ".git").path,
    workTree = root.path,
    namespaceWithPath = "group/project",
    instanceUrl = CONN.instanceUrl,
    webUrl = "https://gitlab.example.com/group/project",
    remoteName = "origin",
    projectId = "group%2Fproject",
  )

  val attempt = LineCommentAttempt(
    apiClient = apiClient,
    captureConnection = {
      captures++
      captured()
    },
    candidateContexts = {
      contextReads++
      contexts
    },
    readBranch = { gitDir ->
      branchReads += gitDir
      CurrentBranch(
        name = "feature",
        trackingBranch = "feature",
        hasUpstream = true,
        upstreamRemote = "origin",
        headSha = headSha,
      )
    },
    lookupMr = { _, _, conn ->
      lookups += conn
      mr
    },
    canCreateNote = { conn, ns, iid ->
      permissionChecks += listOf(conn, ns, iid)
      canCreate
    },
    getLatestMrVersion = { id, iid, conn ->
      versionCalls += listOf(id, iid, conn)
      version()
    },
    openRepository = { repository },
    checkBody = { repo, relPath, file, text, charset ->
      bodyChecks += listOf(repo, relPath, file, text, charset)
      body
    },
    createDiffNote = { conn, gid, text, pos ->
      val s = Sent(conn, gid, text, pos)
      sent += s
      send(s)
    },
    registryActive = { registryActive },
    registryEpoch = { registryEpoch },
  )

  fun line(oneBasedLine: Int = 2, lineCount: Int = 3, filePath: File = file) =
    LineSnapshot(filePath, oneBasedLine, lineCount, TEXT, Charsets.UTF_8)

  fun run(
    snapshot: LineSnapshot = line(),
    session: ReviewSessionSnapshot? = null,
    target: AttemptTarget = AttemptTarget(),
  ): DiscussionWriteOutcome = attempt.run(snapshot, session, target, BODY, startEpoch = 0L)
}

class LineCommentAttemptTest : DescribeSpec({
  lateinit var h: Harness

  beforeEach { h = Harness(tempdir()) }

  describe("a cleared attempt without a session (design §9.3)") {
    it("sends the added line's position over the pinned connection and records the MR target") {
      val target = AttemptTarget()

      h.run(target = target) shouldBe DiscussionWriteOutcome.Success

      val sent = h.sent.single()
      sent.connection shouldBeSameInstanceAs h.pinned
      sent.mrGid shouldBe "gid://gitlab/MergeRequest/900"
      sent.body shouldBe BODY
      sent.position shouldBe position(newLine = 2)
      sent.position.containsKey("oldLine") shouldBe false
      target.value shouldBe (EXPECTED_IDENTITY to EXPECTED_REF)
    }

    it("sends oldLine for an unchanged first line") {
      h.run(h.line(oneBasedLine = 1)) shouldBe DiscussionWriteOutcome.Success

      h.sent.single().position shouldBe position(newLine = 1, oldLine = 1)
    }

    it("keeps a renamed entry's old path in the position") {
      h.version = { mrVersion(diffs = listOf(entry(oldPath = "src/Old.kt"))) }

      h.run(h.line(oneBasedLine = 3)) shouldBe DiscussionWriteOutcome.Success

      h.sent.single().position shouldBe position(newLine = 3, oldLine = 2, oldPath = "src/Old.kt")
    }

    it("captures the connection once and uses it for every gate request") {
      h.run()

      h.captures shouldBe 1
      h.lookups.single() shouldBeSameInstanceAs CONN
      h.permissionChecks.single() shouldBe listOf(CONN, "group/project", 42L)
      h.versionCalls.single() shouldBe listOf("7", 42L, CONN)
    }

    it("checks the body of the file at its repository-relative path") {
      h.run()

      h.branchReads.single() shouldBe File(h.workTree, ".git")
      h.bodyChecks.single() shouldBe listOf(h.repository, PATH, h.file, TEXT, Charsets.UTF_8)
    }

    it("resolves a file in a nested repository to the innermost one") {
      val inner = File(h.workTree, "sub").apply { mkdirs() }
      val innerFile = File(inner, PATH).apply {
        parentFile.mkdirs()
        writeText(TEXT)
      }
      h.contexts = listOf(h.context(h.workTree), h.context(inner))

      h.run(h.line(filePath = innerFile)) shouldBe DiscussionWriteOutcome.Success

      h.branchReads.single() shouldBe File(inner, ".git")
      h.bodyChecks.single()[1] shouldBe PATH
    }
  }

  describe("a cleared attempt with a session") {
    it("targets the session's MR without looking up the current branch") {
      val session = sessionSnapshot()
      val target = AttemptTarget()

      h.run(session = session, target = target) shouldBe DiscussionWriteOutcome.Success

      h.lookups shouldBe emptyList()
      h.permissionChecks.single() shouldBe listOf(CONN, "session/ns", 42L)
      h.versionCalls.single() shouldBe listOf("7", 42L, CONN)
      target.value shouldBe (session.identity to session.mrRef)
      h.sent.single().position shouldBe position(newLine = 2)
    }

    it("rejects with GateRejected, before any gate runs, when the connection is not the session's") {
      val session = sessionSnapshot(EXPECTED_IDENTITY.copy(authFingerprint = "fp-other"))

      h.run(session = session) shouldBe DiscussionWriteOutcome.GateRejected

      h.contextReads shouldBe 0
      h.branchReads shouldBe emptyList()
      h.sent shouldBe emptyList()
    }
  }

  describe("gate refusals: Rejected, nothing sent, no MR target recorded") {
    /** [run] must pass the holder it is given to the attempt, so the asserted holder is the written one. */
    fun expectRejected(message: String, run: (AttemptTarget) -> DiscussionWriteOutcome) {
      val target = AttemptTarget()
      run(target) shouldBe DiscussionWriteOutcome.Rejected(message)
      h.sent shouldBe emptyList()
      target.value.shouldBeNull()
    }

    it("G5: no workspace repository contains the file") {
      h.contexts = emptyList()
      expectRejected(LineCommentAttempt.NO_REPOSITORY_MESSAGE) { h.run(target = it) }
    }

    it("G5: the only repository does not contain the file") {
      h.contexts = listOf(h.context(tempdir()))
      expectRejected(LineCommentAttempt.NO_REPOSITORY_MESSAGE) { h.run(target = it) }
    }

    it("with a session: the file is not the session's file") {
      val session = sessionSnapshot(EXPECTED_IDENTITY.copy(newPath = "src/Other.kt"))
      expectRejected(LineCommentAttempt.FILE_MISMATCH_MESSAGE) { h.run(session = session, target = it) }
    }

    it("G6: the current branch has no open MR") {
      h.mr = null
      expectRejected(LineCommentAttempt.NO_MERGE_REQUEST_MESSAGE) { h.run(target = it) }
    }

    it("G6: the MR has no reference to address its discussions with") {
      h.mr = mergeRequest(references = null)
      expectRejected(LineCommentAttempt.NO_MERGE_REQUEST_MESSAGE) { h.run(target = it) }
    }

    it("G6: the MR has no head sha") {
      h.mr = mergeRequest(sha = null)
      expectRejected(LineCommentAttempt.NO_MERGE_REQUEST_MESSAGE) { h.run(target = it) }
    }

    it("G6b: the user may not create notes on the MR") {
      h.canCreate = false
      expectRejected(LineCommentAttempt.NO_PERMISSION_MESSAGE) { h.run(target = it) }
    }

    it("G7: HEAD is not the MR's head") {
      h.headSha = "local-commit"
      expectRejected(LineCommentAttempt.CHECKOUT_FIRST_MESSAGE) { h.run(target = it) }
      h.bodyChecks shouldBe emptyList()
    }

    it("G7 with a session: HEAD is not the session's head") {
      h.headSha = "local-commit"
      expectRejected(LineCommentAttempt.CHECKOUT_FIRST_MESSAGE) { h.run(session = sessionSnapshot(), target = it) }
    }

    it("G7: HEAD cannot be read") {
      h.headSha = null
      expectRejected(LineCommentAttempt.CHECKOUT_FIRST_MESSAGE) { h.run(target = it) }
    }

    it("G8: the editor text does not map onto HEAD") {
      h.body = BodyIdentity.Different(BodyMismatch.STATUS_NOT_CLEAN)
      expectRejected(LineCommentAttempt.FILE_MISMATCH_MESSAGE) { h.run(target = it) }
      h.versionCalls shouldBe emptyList()
    }

    it("G9: the MR has no version") {
      h.version = { null }
      expectRejected(VersionDiff.Missing.NO_VERSION.message) { h.run(target = it) }
    }

    it("G9: the latest version's head is not the checked HEAD") {
      h.version = { mrVersion(head = "head2") }
      expectRejected(VersionDiff.Missing.HEAD_MOVED.message) { h.run(target = it) }
    }

    it("G9: the version has no entry for the file") {
      h.version = { mrVersion(diffs = listOf(entry(newPath = "src/Other.kt"))) }
      expectRejected(VersionDiff.Missing.NO_ENTRY.message) { h.run(target = it) }
    }

    it("G9: the file's diff is too large to map lines") {
      h.version = { mrVersion(diffs = listOf(entry(diff = "", tooLarge = true))) }
      expectRejected(LineCommentAttempt.DIFF_UNAVAILABLE_MESSAGE) { h.run(target = it) }
    }

    it("G9: the line is past the end of the document") {
      expectRejected(LineCommentAttempt.LINE_OUT_OF_RANGE_MESSAGE) {
        h.run(h.line(oneBasedLine = 4, lineCount = 3), target = it)
      }
    }

    it("G9: an empty document (lineCount 0) has no line to comment on") {
      expectRejected(LineCommentAttempt.LINE_OUT_OF_RANGE_MESSAGE) {
        h.run(h.line(oneBasedLine = 1, lineCount = 0), target = it)
      }
    }
  }

  describe("connection and lifecycle") {
    it("rejects with GateRejected when the connection cannot be captured") {
      h.captured = { throw UnstableConnectionException() }

      h.run() shouldBe DiscussionWriteOutcome.GateRejected

      h.contextReads shouldBe 0
      h.sent shouldBe emptyList()
    }

    it("rejects with GateRejected when the account changed just before sending") {
      h.pinned = CONN.copy(authFingerprint = "fp-other")
      val target = AttemptTarget()

      h.run(target = target) shouldBe DiscussionWriteOutcome.GateRejected

      h.sent shouldBe emptyList()
      target.value shouldBe (EXPECTED_IDENTITY to EXPECTED_REF)
    }

    it("aborts without sending when the plugin lifecycle moved on") {
      h.registryEpoch = 1L

      h.run() shouldBe DiscussionWriteOutcome.Aborted

      h.sent shouldBe emptyList()
    }

    it("aborts without sending when the plugin is stopping") {
      h.registryActive = false

      h.run() shouldBe DiscussionWriteOutcome.Aborted

      h.sent shouldBe emptyList()
    }
  }

  describe("failures") {
    it("classifies a send failure with classifyWriteFailure") {
      val definite = GraphQlException(hasDataKey = false, messages = listOf("no"))
      h.send = { throw definite }
      h.run() shouldBe DiscussionWriteOutcome.Definite(definite)

      val ambiguous = IOException("reset")
      h.send = { throw ambiguous }
      h.run() shouldBe DiscussionWriteOutcome.Ambiguous(ambiguous)
    }

    it("treats a failure before sending as Definite: nothing was sent, so a retry is safe") {
      val cause = IOException("versions endpoint down")
      h.version = { throw cause }
      val target = AttemptTarget()

      h.run(target = target) shouldBe DiscussionWriteOutcome.Definite(cause)

      h.sent shouldBe emptyList()
      target.value.shouldBeNull()
    }

    it("rethrows cancellation, before and during the send") {
      h.version = { throw CancellationException("cancelled") }
      shouldThrow<CancellationException> { h.run() }

      h.version = { mrVersion() }
      h.send = { throw CancellationException("cancelled") }
      shouldThrow<CancellationException> { h.run() }
    }
  }

  describe("G6b production check") {
    it("requests exactly one discussions page and answers with its createNote permission") {
      val service = mockk<DiscussionService>()
      every { service.getDiscussions(any(), any(), any(), any(), any(), any(), any()) } returns
        DiscussionsReadResult(canCreateNote = true, discussions = emptyList(), truncation = TruncationReason.PAGE_LIMIT)

      fetchCanCreateNote(service, CONN, "group/project", 42L) shouldBe true

      verify(exactly = 1) {
        service.getDiscussions(CONN, "group/project", 42L, DiscussionService.DISCUSSIONS_DEADLINE, any(), any(), 1)
      }
    }
  }

  describe("retries re-evaluate every gate (A14)") {
    it("does not send the second attempt when HEAD moved in between") {
      h.run() shouldBe DiscussionWriteOutcome.Success
      h.headSha = "head2"

      h.run() shouldBe DiscussionWriteOutcome.Rejected(LineCommentAttempt.CHECKOUT_FIRST_MESSAGE)

      h.sent.size shouldBe 1
      h.branchReads.size shouldBe 2
    }

    it("does not send the second attempt when the MR got a new version in between") {
      h.run() shouldBe DiscussionWriteOutcome.Success
      h.version = { mrVersion(head = "head2") }

      h.run() shouldBe DiscussionWriteOutcome.Rejected(VersionDiff.Missing.HEAD_MOVED.message)

      h.sent.size shouldBe 1
    }

    it("evaluates the gates again on every call even when nothing changed") {
      h.run()
      h.run()

      h.captures shouldBe 2
      h.permissionChecks.size shouldBe 2
      h.bodyChecks.size shouldBe 2
      h.versionCalls.size shouldBe 2
      h.sent.size shouldBe 2
    }

    it("clears the recorded MR target when a later attempt fails a gate") {
      val target = AttemptTarget()
      h.run(target = target)
      target.value.shouldBeInstanceOf<Pair<SessionIdentity, MergeRequestRef>>()
      h.canCreate = false

      h.run(target = target)

      target.value.shouldBeNull()
    }
  }
})
