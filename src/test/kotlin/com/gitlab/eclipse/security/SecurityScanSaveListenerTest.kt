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
    it("suppresses the dirty->clean transition that happens during a revert") {
      val guard = RevertGuard()
      guard.aboutToBeReplaced("editorA")
      guard.shouldScanOnClean("editorA") shouldBe false
      guard.contentReplaced("editorA")
    }

    it("allows a normal save") {
      RevertGuard().shouldScanOnClean("editorA") shouldBe true
    }

    it("allows the next save once the revert has completed") {
      val guard = RevertGuard()
      guard.aboutToBeReplaced("editorA")
      guard.contentReplaced("editorA")
      guard.shouldScanOnClean("editorA") shouldBe true
      guard.entryCountForTest() shouldBe 0
    }

    it("keeps a revert on one element from suppressing a save on another") {
      val guard = RevertGuard()
      guard.aboutToBeReplaced("editorA")
      guard.shouldScanOnClean("editorB") shouldBe true
      guard.shouldScanOnClean("editorA") shouldBe false
    }

    it("does not leak entries when contentReplaced never arrives") {
      var now = 0L
      val guard = RevertGuard(nowMillis = { now })
      guard.aboutToBeReplaced("editorA")
      guard.entryCountForTest() shouldBe 1

      // Still inside the window: the record stands and a revert is still assumed.
      now = REVERT_WINDOW_MS - 1
      guard.shouldScanOnClean("editorA") shouldBe false
      guard.entryCountForTest() shouldBe 1

      // Past the window with no `contentReplaced`: the stale record is dropped by the next
      // decision, and that decision is the first one to see a clean slate again.
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

    it("does not launch a scan for the dirty->clean transition of a revert") {
      val launches: Launches = mutableListOf()
      val subject = listener(launches, active = "a")
      subject.elementContentAboutToBeReplaced("a")
      subject.elementDirtyStateChanged("a", false)
      launches.size shouldBe 0
    }

    it("launches again on a real save once the revert has completed") {
      val launches: Launches = mutableListOf()
      val subject = listener(launches, active = "a")
      subject.elementContentAboutToBeReplaced("a")
      subject.elementDirtyStateChanged("a", false)
      subject.elementContentReplaced("a")
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

    it("does not treat a deleted element as a save") {
      val launches: Launches = mutableListOf()
      listener(launches, active = "a").elementDeleted("a")
      launches.size shouldBe 0
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
