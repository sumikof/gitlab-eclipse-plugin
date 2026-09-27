package com.gitlab.eclipse.mergerequests.review

import com.gitlab.eclipse.mergerequests.review.ReviewSessionState.BeginResult
import com.gitlab.eclipse.mergerequests.review.ReviewSessionState.Phase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private val IDENTITY = SessionIdentity(
  instanceUrl = "https://gitlab.example.com",
  authFingerprint = "fp-1",
  projectId = 7L,
  mrIid = 42L,
  headSha = "head1",
  newPath = "src/Foo.kt",
)

private val REF = MergeRequestRef(
  instanceUrl = "https://gitlab.example.com",
  authFingerprint = "fp-1",
  projectId = 7L,
  mrIid = 42L,
  mrGid = "gid://gitlab/MergeRequest/900",
  namespaceWithPath = "group/project",
)

private fun snapshotFor(identity: SessionIdentity) = ReviewSessionSnapshot(
  identity = identity,
  mrRef = REF,
  baseSha = "base1",
  startSha = "start1",
  headSha = identity.headSha,
  oldPath = identity.newPath,
  newPath = identity.newPath,
  lineMap = DiffLineMap.Identity,
  canCreateNote = true,
  placements = emptyList(),
)

/** Documents and editors are opaque keys to the state: plain strings stand in for IDocument / ITextEditor. */
private const val DOC = "doc-1"
private const val E1 = "editor-1"
private const val E2 = "editor-2"

private fun ReviewSessionState<String, String>.startLoad(editor: String, identity: SessionIdentity = IDENTITY) =
  begin(DOC, editor, identity).shouldBeInstanceOf<BeginResult.StartLoad<String, String>>()

class ReviewSessionStateTest : DescribeSpec({
  describe("begin") {
    it("starts a load for a document with no session, in LOADING, replacing nothing") {
      val state = ReviewSessionState<String, String>()

      val result = state.startLoad(E1)

      result.document shouldBe DOC
      result.replaced.shouldBeNull()
      state.phase(DOC) shouldBe Phase.LOADING
      state.snapshot(DOC).shouldBeNull()
    }

    it("joins a LOADING session with the same identity without starting another load") {
      val state = ReviewSessionState<String, String>()
      state.startLoad(E1)

      state.begin(DOC, E2, IDENTITY) shouldBe BeginResult.Joined(DOC)
      state.phase(DOC) shouldBe Phase.LOADING
    }

    it("joins a READY session with the same identity") {
      val state = ReviewSessionState<String, String>()
      val load = state.startLoad(E1)
      state.onLoaded(DOC, load.generation, snapshotFor(IDENTITY))

      state.begin(DOC, E2, IDENTITY) shouldBe BeginResult.Joined(DOC)
      state.phase(DOC) shouldBe Phase.READY
    }

    val variants = listOf(
      "instanceUrl" to IDENTITY.copy(instanceUrl = "https://other.example.com"),
      "authFingerprint" to IDENTITY.copy(authFingerprint = "fp-2"),
      "projectId" to IDENTITY.copy(projectId = 8L),
      "mrIid" to IDENTITY.copy(mrIid = 43L),
      "headSha" to IDENTITY.copy(headSha = "head2"),
      "newPath" to IDENTITY.copy(newPath = "src/Bar.kt"),
    )
    variants.forEach { (component, other) ->
      it("replaces the READY session when only $component differs, handing back the old snapshot (§9.5)") {
        val state = ReviewSessionState<String, String>()
        val first = state.startLoad(E1)
        val old = snapshotFor(IDENTITY)
        state.onLoaded(DOC, first.generation, old)

        val second = state.startLoad(E2, other)

        second.replaced shouldBe old
        (second.generation > first.generation) shouldBe true
        state.phase(DOC) shouldBe Phase.LOADING
        state.snapshot(DOC).shouldBeNull()
      }
    }

    it("carries the connected editors over to the replacing session (§9.5 step 4)") {
      val state = ReviewSessionState<String, String>()
      val first = state.startLoad(E1)
      state.onLoaded(DOC, first.generation, snapshotFor(IDENTITY))
      val other = IDENTITY.copy(headSha = "head2")

      val second = state.startLoad(E2, other)

      state.onLoaded(DOC, second.generation, snapshotFor(other))!! shouldContainExactlyInAnyOrder listOf(E1, E2)
    }

    it("discards the replaced session's in-flight load") {
      val state = ReviewSessionState<String, String>()
      val first = state.startLoad(E1)
      state.startLoad(E1, IDENTITY.copy(mrIid = 43L))

      state.onLoaded(DOC, first.generation, snapshotFor(IDENTITY)).shouldBeNull()
      state.phase(DOC) shouldBe Phase.LOADING
    }
  }

  describe("onLoaded") {
    it("applies the latest load to READY and returns every connected editor") {
      val state = ReviewSessionState<String, String>()
      val load = state.startLoad(E1)
      state.begin(DOC, E2, IDENTITY)
      val snapshot = snapshotFor(IDENTITY)

      state.onLoaded(DOC, load.generation, snapshot)!! shouldContainExactly listOf(E1, E2)
      state.phase(DOC) shouldBe Phase.READY
      state.snapshot(DOC) shouldBe snapshot
    }

    it("discards a load whose generation is no longer the latest") {
      val state = ReviewSessionState<String, String>()
      val first = state.startLoad(E1)
      state.onLoadFailed(DOC, first.generation)
      val second = state.startLoad(E1)

      state.onLoaded(DOC, first.generation, snapshotFor(IDENTITY)).shouldBeNull()
      state.phase(DOC) shouldBe Phase.LOADING
      state.snapshot(DOC).shouldBeNull()
      state.onLoaded(DOC, second.generation, snapshotFor(IDENTITY))!! shouldContainExactly listOf(E1)
    }

    it("applies to the remaining editor when the initiating editor closed during the load (A21)") {
      val state = ReviewSessionState<String, String>()
      val load = state.startLoad(E1)
      state.begin(DOC, E2, IDENTITY)

      state.editorClosed(DOC, E1).shouldBeNull()

      state.onLoaded(DOC, load.generation, snapshotFor(IDENTITY))!! shouldContainExactly listOf(E2)
      state.phase(DOC) shouldBe Phase.READY
    }

    it("discards the load when every connected editor closed during it") {
      val state = ReviewSessionState<String, String>()
      val load = state.startLoad(E1)
      state.editorClosed(DOC, E1)

      state.onLoaded(DOC, load.generation, snapshotFor(IDENTITY)).shouldBeNull()
      state.phase(DOC).shouldBeNull()
      state.snapshot(DOC).shouldBeNull()
    }

    it("discards a load for a document that never had a session") {
      val state = ReviewSessionState<String, String>()

      state.onLoaded(DOC, 1L, snapshotFor(IDENTITY)).shouldBeNull()
      state.phase(DOC).shouldBeNull()
    }
  }

  describe("onLoadFailed (§9.1.2)") {
    it("moves the latest load to FAILED and keeps the connected editors") {
      val state = ReviewSessionState<String, String>()
      val load = state.startLoad(E1)
      state.begin(DOC, E2, IDENTITY)

      state.onLoadFailed(DOC, load.generation)

      state.phase(DOC) shouldBe Phase.FAILED
      state.snapshot(DOC).shouldBeNull()
    }

    it("ignores a failure whose generation is no longer the latest") {
      val state = ReviewSessionState<String, String>()
      val first = state.startLoad(E1)
      state.startLoad(E1, IDENTITY.copy(headSha = "head2"))

      state.onLoadFailed(DOC, first.generation)

      state.phase(DOC) shouldBe Phase.LOADING
    }

    it("reloads a FAILED session on begin with the same identity, keeping the editors (A25)") {
      val state = ReviewSessionState<String, String>()
      val first = state.startLoad(E1)
      state.begin(DOC, E2, IDENTITY)
      state.onLoadFailed(DOC, first.generation)

      val retry = state.startLoad(E1)
      state.editorClosed(DOC, E1).shouldBeNull()

      (retry.generation > first.generation) shouldBe true
      retry.replaced.shouldBeNull()
      state.phase(DOC) shouldBe Phase.LOADING
      state.onLoaded(DOC, retry.generation, snapshotFor(IDENTITY))!! shouldContainExactly listOf(E2)
      state.phase(DOC) shouldBe Phase.READY
    }

    it("keeps the last applied snapshot when a refresh of a READY session fails") {
      val state = ReviewSessionState<String, String>()
      val load = state.startLoad(E1)
      val snapshot = snapshotFor(IDENTITY)
      state.onLoaded(DOC, load.generation, snapshot)
      val refresh = state.refresh(DOC, IDENTITY)!!

      state.onLoadFailed(DOC, refresh)

      state.phase(DOC) shouldBe Phase.FAILED
      state.snapshot(DOC) shouldBe snapshot
    }
  }

  describe("refresh (FR-10)") {
    it("advances the generation of a matching session so an older load is discarded") {
      val state = ReviewSessionState<String, String>()
      val load = state.startLoad(E1)
      state.onLoaded(DOC, load.generation, snapshotFor(IDENTITY))

      val refresh = state.refresh(DOC, IDENTITY)!!

      (refresh > load.generation) shouldBe true
      state.phase(DOC) shouldBe Phase.READY
      state.onLoaded(DOC, load.generation, snapshotFor(IDENTITY)).shouldBeNull()
      state.onLoaded(DOC, refresh, snapshotFor(IDENTITY))!! shouldContainExactly listOf(E1)
    }

    it("refuses when the document's session has another identity or there is none (§9.5)") {
      val state = ReviewSessionState<String, String>()
      state.refresh(DOC, IDENTITY).shouldBeNull()
      state.startLoad(E1)

      state.refresh(DOC, IDENTITY.copy(headSha = "head2")).shouldBeNull()
    }
  }

  describe("editorClosed (§9.6)") {
    it("keeps the session while another editor is connected") {
      val state = ReviewSessionState<String, String>()
      val load = state.startLoad(E1)
      state.begin(DOC, E2, IDENTITY)
      state.onLoaded(DOC, load.generation, snapshotFor(IDENTITY))

      state.editorClosed(DOC, E1).shouldBeNull()

      state.phase(DOC) shouldBe Phase.READY
    }

    it("removes the session with the last editor and returns its snapshot to release") {
      val state = ReviewSessionState<String, String>()
      val load = state.startLoad(E1)
      val snapshot = snapshotFor(IDENTITY)
      state.onLoaded(DOC, load.generation, snapshot)

      state.editorClosed(DOC, E1) shouldBe snapshot

      state.phase(DOC).shouldBeNull()
      state.snapshot(DOC).shouldBeNull()
    }

    it("removes a session that never loaded with the last editor, with nothing to release") {
      val state = ReviewSessionState<String, String>()
      state.startLoad(E1)

      state.editorClosed(DOC, E1).shouldBeNull()

      state.phase(DOC).shouldBeNull()
    }

    it("ignores an editor that is not connected") {
      val state = ReviewSessionState<String, String>()
      state.startLoad(E1)

      state.editorClosed(DOC, E2).shouldBeNull()

      state.phase(DOC) shouldBe Phase.LOADING
    }

    it("starts a fresh load once the removed document is begun again") {
      val state = ReviewSessionState<String, String>()
      val load = state.startLoad(E1)
      state.onLoaded(DOC, load.generation, snapshotFor(IDENTITY))
      state.editorClosed(DOC, E1)

      val again = state.startLoad(E1)

      again.replaced.shouldBeNull()
      (again.generation > load.generation) shouldBe true
    }
  }

  describe("clear") {
    it("removes every session, returns their documents, and discards loads still in flight") {
      val state = ReviewSessionState<String, String>()
      val load = state.startLoad(E1)
      state.begin("doc-2", E2, IDENTITY)

      state.clear() shouldContainExactlyInAnyOrder listOf(DOC, "doc-2")

      state.phase(DOC).shouldBeNull()
      state.phase("doc-2").shouldBeNull()
      state.onLoaded(DOC, load.generation, snapshotFor(IDENTITY)).shouldBeNull()
    }

    it("never reuses a generation after clear, so a pre-clear load cannot land on a new session") {
      val state = ReviewSessionState<String, String>()
      val before = state.startLoad(E1)
      state.clear()

      val after = state.startLoad(E1)

      (after.generation > before.generation) shouldBe true
      state.onLoaded(DOC, before.generation, snapshotFor(IDENTITY)).shouldBeNull()
    }
  }
})
