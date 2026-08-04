package com.gitlab.eclipse.security

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

/** Recorded `launch` calls, so a test can assert both "how many" and "with what". */
private typealias Launches = MutableList<Pair<String?, SecurityScanSource>>

/**
 * Builds a listener with every workbench touching collaborator replaced by a lambda.
 *
 * The point of the split (design constraint: SWT cannot run in this container) is that the whole
 * save decision is reachable headless: elements are plain strings, the key is the string itself and
 * the URI is derived from it, so the only thing under test is the order and presence of the gates.
 */
private fun listener(
  launches: Launches,
  onSave: Boolean = true,
  active: Any? = null,
  guard: RevertGuard = RevertGuard(),
  uriOf: (Any) -> String? = { "file:/w/$it" },
) = SecurityScanSaveListener(
  guard = guard,
  scanOnSaveEnabled = { onSave },
  keyOf = { it.toString() },
  uriOf = uriOf,
  isActiveEditorInput = { it == active },
  launch = { uri, source ->
    launches += uri to source
    SecurityScanLaunchOutcome.SENT
  },
)

class SecurityScanSaveListenerTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  beforeEach {
    DiagnosticGenerationRegistry.resetForTest()
    CommandWaiters.resetForTest()
  }

  afterEach {
    DiagnosticGenerationRegistry.resetForTest()
    CommandWaiters.resetForTest()
  }

  describe("RevertGuard") {
    // The platform's real order. Verified from bytecode, not from memory:
    // org.eclipse.core.filebuffers-3.8.500 ResourceFileBuffer.revert -> handleFileContentChanged,
    // and ResourceTextFileBuffer.handleFileContentChanged fires, unconditionally and in this order,
    // fireBufferContentAboutToBeReplaced (@90), fireBufferContentReplaced (@182),
    // fireDirtyStateChanged (@257). org.eclipse.ui.editors-3.20.200
    // TextFileDocumentProvider$FileBufferListener forwards all three synchronously without
    // reordering. So `contentReplaced` arrives BEFORE the dirty edge that has to be suppressed.
    it("suppresses the dirty edge of a revert delivered in the platform's real order") {
      val guard = RevertGuard()
      guard.aboutToBeReplaced("editorA")
      guard.contentReplaced("editorA")
      guard.shouldScanOnClean("editorA") shouldBe false
    }

    it("suppresses the dirty edge even when contentReplaced arrives after it") {
      val guard = RevertGuard()
      guard.aboutToBeReplaced("editorA")
      guard.shouldScanOnClean("editorA") shouldBe false
      // The record was consumed by the edge above; a late `contentReplaced` must not resurrect it
      // and swallow the user's next real save.
      guard.contentReplaced("editorA")
      guard.shouldScanOnClean("editorA") shouldBe true
    }

    it("allows a normal save") {
      RevertGuard().shouldScanOnClean("editorA") shouldBe true
    }

    it("allows the save that follows a completed revert") {
      val guard = RevertGuard()
      guard.aboutToBeReplaced("editorA")
      guard.contentReplaced("editorA")
      guard.shouldScanOnClean("editorA") shouldBe false
      guard.shouldScanOnClean("editorA") shouldBe true
      guard.entryCountForTest() shouldBe 0
    }

    it("re-stamps the record on contentReplaced so a slow revert is not expired mid-flight") {
      var now = 0L
      val guard = RevertGuard(nowMillis = { now })
      guard.aboutToBeReplaced("editorA")
      now = REVERT_WINDOW_MS - 1
      guard.contentReplaced("editorA")
      // Past the window measured from `aboutToBeReplaced`, but only 2ms past the re-stamp.
      now = REVERT_WINDOW_MS + 1
      guard.shouldScanOnClean("editorA") shouldBe false
    }

    it("discards a record outright") {
      val guard = RevertGuard()
      guard.aboutToBeReplaced("editorA")
      guard.discard("editorA")
      guard.entryCountForTest() shouldBe 0
      guard.shouldScanOnClean("editorA") shouldBe true
    }

    it("keeps a revert on one element from suppressing a save on another") {
      val guard = RevertGuard()
      guard.aboutToBeReplaced("editorA")
      guard.shouldScanOnClean("editorB") shouldBe true
      guard.shouldScanOnClean("editorA") shouldBe false
    }

    it("still suppresses the dirty edge inside the window when contentReplaced never arrives") {
      var now = 0L
      val guard = RevertGuard(nowMillis = { now })
      guard.aboutToBeReplaced("editorA")
      guard.entryCountForTest() shouldBe 1
      now = REVERT_WINDOW_MS - 1
      guard.shouldScanOnClean("editorA") shouldBe false
    }

    it("does not leak entries when neither contentReplaced nor a dirty edge ever arrives") {
      var now = 0L
      val guard = RevertGuard(nowMillis = { now })
      guard.aboutToBeReplaced("editorA")
      guard.entryCountForTest() shouldBe 1

      // Past the window with nothing to consume the record: the next decision drops it, and is
      // itself the first one to see a clean slate again.
      now = REVERT_WINDOW_MS + 1
      guard.shouldScanOnClean("editorA") shouldBe true
      guard.entryCountForTest() shouldBe 0
    }

    it("drops a stale entry even when the next decision is about a different element") {
      var now = 0L
      val guard = RevertGuard(nowMillis = { now })
      guard.aboutToBeReplaced("editorA")
      now = REVERT_WINDOW_MS + 1
      guard.shouldScanOnClean("editorB") shouldBe true
      guard.entryCountForTest() shouldBe 0
    }
  }

  describe("SecurityScanSaveListener") {
    it("launches a SAVE scan for the active editor's own save") {
      val launches: Launches = mutableListOf()
      listener(launches, active = "a").elementDirtyStateChanged("a", false)
      launches shouldBe listOf("file:/w/a" to SecurityScanSource.SAVE)
    }

    it("does not launch anything when scanFileOnSave is off") {
      val launches: Launches = mutableListOf()
      listener(launches, onSave = false, active = "a").elementDirtyStateChanged("a", false)
      launches.size shouldBe 0
    }

    // The order the platform really delivers (see the RevertGuard block above): the replacement is
    // announced, then completed, and only then does the buffer go clean. This is the test that
    // stands between `File > Revert` and an upload the user never asked for.
    it("does not launch a scan for a revert delivered in the platform's real order") {
      val launches: Launches = mutableListOf()
      val subject = listener(launches, active = "a")
      subject.elementContentAboutToBeReplaced("a")
      subject.elementContentReplaced("a")
      subject.elementDirtyStateChanged("a", false)
      launches.size shouldBe 0
    }

    it("does not launch a scan when the dirty edge precedes contentReplaced") {
      val launches: Launches = mutableListOf()
      val subject = listener(launches, active = "a")
      subject.elementContentAboutToBeReplaced("a")
      subject.elementDirtyStateChanged("a", false)
      subject.elementContentReplaced("a")
      launches.size shouldBe 0
    }

    it("launches again on a real save once the revert has completed") {
      val launches: Launches = mutableListOf()
      val subject = listener(launches, active = "a")
      subject.elementContentAboutToBeReplaced("a")
      subject.elementContentReplaced("a")
      subject.elementDirtyStateChanged("a", false)
      subject.elementDirtyStateChanged("a", false)
      launches shouldBe listOf("file:/w/a" to SecurityScanSource.SAVE)
    }

    it("launches at most once for a Save All across several dirty editors") {
      val launches: Launches = mutableListOf()
      val subject = listener(launches, active = "b")
      listOf("a", "b", "c").forEach { subject.elementDirtyStateChanged(it, false) }
      launches shouldBe listOf("file:/w/b" to SecurityScanSource.SAVE)
    }

    it("ignores the transition that makes an element dirty") {
      val launches: Launches = mutableListOf()
      listener(launches, active = "a").elementDirtyStateChanged("a", true)
      launches.size shouldBe 0
    }

    it("ignores an element that has no file URI") {
      val launches: Launches = mutableListOf()
      listener(launches, active = "a", uriOf = { null }).elementDirtyStateChanged("a", false)
      launches.size shouldBe 0
    }

    it("ignores a null element") {
      val launches: Launches = mutableListOf()
      val subject = listener(launches, active = "a")
      subject.elementContentAboutToBeReplaced(null)
      subject.elementDirtyStateChanged(null, false)
      subject.elementContentReplaced(null)
      launches.size shouldBe 0
    }

    it("does not treat a deleted element as a save, and drops the record it left behind") {
      val launches: Launches = mutableListOf()
      val guard = RevertGuard()
      val subject = listener(launches, active = "a", guard = guard)
      subject.elementContentAboutToBeReplaced("a")
      subject.elementDeleted("a")
      launches.size shouldBe 0
      // Nothing can consume the record now that the element is gone, so it has to go here.
      guard.entryCountForTest() shouldBe 0
    }

    it("survives a collaborator that throws instead of aborting the platform's listener loop") {
      // TextFileDocumentProvider$FileBufferListener.dirtyStateChanged iterates its listeners with
      // no exception table, so one throw from us skips every remaining listener — including the
      // editor's own dirty handling, which would leave a stale `*` on the user's tab.
      val subject = SecurityScanSaveListener(
        scanOnSaveEnabled = { error("preference store is gone") },
        keyOf = { error("editor input is gone") },
        uriOf = { "file:/w/$it" },
        isActiveEditorInput = { true },
        launch = { _, _ -> SecurityScanLaunchOutcome.SENT },
      )
      subject.elementDirtyStateChanged("a", false)
      subject.elementContentAboutToBeReplaced("a")
      subject.elementContentReplaced("a")
      subject.elementDeleted("a")
      subject.elementMoved("a", "b")
    }

    it("drops the revert record of an element a move left behind") {
      val launches: Launches = mutableListOf()
      val guard = RevertGuard()
      val subject = listener(launches, active = "a", guard = guard)
      subject.elementContentAboutToBeReplaced("a")
      // Nothing will ever send `elementContentReplaced` for the old identity now, so the record
      // has to go here rather than wait out the window.
      subject.elementMoved("a", "b")
      guard.entryCountForTest() shouldBe 0
    }
  }
})
