package com.gitlab.eclipse.security.details

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.LanguageServerHandle
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.plugins.messages.ExtensionToPluginNotification
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKeys
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle

private const val MARKER = "SECRET-MARKER-7f3a"
private const val PATH = "/home/$MARKER/project/src/App.kt"
private const val LINE = 7
private const val EPOCH = 3L

/** The user-facing wording, spelled out so a change to the production constants is a visible test change. */
private const val NO_FINDING_TEXT =
  "No GitLab security finding on this line. Run a remote scan (SAST) on the file first."
private const val STALE_TEXT = "The security findings for this file changed. Run the command again."
private const val OPEN_FAILED_TEXT = "Could not open the GitLab vulnerability details. See the Error Log."

private fun finding(line: Int = LINE): Map<String, Any?> = mapOf(
  "name" to "Name $MARKER",
  "severity" to "High",
  "description" to "Description $MARKER",
  "location" to mapOf("start_line" to line, "start_column" to 2),
)

private fun snapshot(vararg findings: Any?): FileVulnerabilities =
  FileVulnerabilities(findings.toList(), 1_700_000_000_000L, EPOCH, "fp")

/** Installs a log that records every message handed to it. */
private fun captureLog(): List<String> {
  val recorded = mutableListOf<String>()
  val log = mockk<ILog>()
  val message = slot<String>()
  val status = slot<IStatus>()
  every { log.error(capture(message)) } answers { recorded += message.captured }
  every { log.error(capture(message), any()) } answers { recorded += message.captured }
  every { log.warn(capture(message)) } answers { recorded += message.captured }
  every { log.warn(capture(message), any()) } answers { recorded += message.captured }
  every { log.info(capture(message)) } answers { recorded += message.captured }
  every { log.info(capture(message), any()) } answers { recorded += message.captured }
  every { log.log(capture(status)) } answers { recorded += status.captured.message }
  every { Platform.getLog(any<Bundle>()) } returns log
  every { Platform.getLog(any<Class<*>>()) } returns log
  return recorded
}

/**
 * One client over a single-threaded test scheduler and a scope built exactly like production's shared
 * scope: a plain [Job], not a `SupervisorJob`, so an escaping failure would cancel it.
 */
private class Fixture(
  var read: (String, Long) -> FileVulnerabilities? = { _, _ -> null },
  var project: (Any?) -> Map<String, Any?>? = VulnerabilityProjection::of,
  var onUiThread: (Runnable) -> Unit = { it.run() },
) {
  val scheduler = TestCoroutineScheduler()
  val parent = Job()
  val scope = CoroutineScope(parent + StandardTestDispatcher(scheduler))
  val lock = Mutex()
  val proxy = mockk<GitLabLanguageServer>(relaxed = true)
  val handle = LanguageServerHandle(proxy, LanguageServerSession(), EPOCH)
  val notified = mutableListOf<String>()
  var opened = 0
  var openTab: () -> Unit = { opened++ }

  val client = SecurityVulnDetailsClient(
    coroutineScope = scope,
    outboundLock = lock,
    read = { p, e -> read(p, e) },
    project = { project(it) },
    onUiThread = { onUiThread(it) },
    notify = { notified += it },
  )

  fun show() {
    client.show(handle, PATH, LINE) { openTab() }
  }

  fun sent(): List<ExtensionToPluginNotification> {
    val captured = mutableListOf<ExtensionToPluginNotification>()
    verify(atLeast = 0) { proxy.pluginNotification(capture(captured)) }
    return captured
  }

  /** The scope still runs new work: the property a leaked failure would destroy. */
  fun scopeStillRuns(): Boolean {
    var ran = false
    scope.launch { ran = true }
    scheduler.advanceUntilIdle()
    return ran && scope.isActive
  }
}

class SecurityVulnDetailsClientTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("the normal path (A22 (iv), A6)") {
    it("sends updateDetails once to the security-vuln-details plugin, then opens the tab once") {
      val stored = snapshot(finding())
      val f = Fixture(read = { _, _ -> stored })
      f.show()
      f.scheduler.advanceUntilIdle()

      val sent = f.sent()
      sent.size shouldBe 1
      sent.single().pluginId shouldBe "security-vuln-details"
      sent.single().type shouldBe "updateDetails"
      @Suppress("UNCHECKED_CAST")
      val payload = sent.single().payload as Map<String, Any?>
      payload.shouldContainKeys("vulnerability", "filePath", "timestamp")
      payload["filePath"] shouldBe "App.kt"
      @Suppress("UNCHECKED_CAST")
      val vulnerability = payload["vulnerability"] as Map<String, Any?>
      vulnerability.shouldContainKeys("name", "severity", "description", "location")
      f.opened shouldBe 1
      f.notified.shouldBeEmpty()
    }

    it("reads with the handle's epoch, not any other") {
      val epochs = mutableListOf<Long>()
      val stored = snapshot(finding())
      val f = Fixture(
        read = { _, e ->
          epochs += e
          stored
        },
      )
      f.show()
      f.scheduler.advanceUntilIdle()
      epochs shouldContainExactly listOf(EPOCH, EPOCH)
    }

    it("returns before any of the work happens, so the UI thread never waits") {
      var reads = 0
      val f = Fixture(
        read = { _, _ ->
          reads++
          snapshot(finding())
        },
      )
      f.show()
      reads shouldBe 0
      f.opened shouldBe 0
      f.scheduler.advanceUntilIdle()
      reads shouldBe 2
    }
  }

  describe("nothing to show") {
    it("with no retained findings: notice, no send, no tab") {
      val f = Fixture(read = { _, _ -> null })
      f.show()
      f.scheduler.advanceUntilIdle()
      f.notified shouldContainExactly listOf(NO_FINDING_TEXT)
      f.sent().shouldBeEmpty()
      f.opened shouldBe 0
    }

    it("with no finding on the cursor line: notice, no send, no tab") {
      val f = Fixture(read = { _, _ -> snapshot(finding(line = LINE + 1)) })
      f.show()
      f.scheduler.advanceUntilIdle()
      f.notified shouldContainExactly listOf(NO_FINDING_TEXT)
      f.sent().shouldBeEmpty()
      f.opened shouldBe 0
    }

    it("when the projection rejects the finding: notice, no send, no tab") {
      val f = Fixture(read = { _, _ -> snapshot(finding()) }, project = { null })
      f.show()
      f.scheduler.advanceUntilIdle()
      f.notified shouldContainExactly listOf(NO_FINDING_TEXT)
      f.sent().shouldBeEmpty()
      f.opened shouldBe 0
    }
  }

  describe("context binding (A22)") {
    it("(i) a reconnect after the read still sends only to the handle's proxy") {
      // The client is given the handle once; a connection that replaces it after the read has its own
      // proxy, which must never be reached from this command.
      val stored = snapshot(finding())
      val replacement = mockk<GitLabLanguageServer>(relaxed = true)
      var wrapperCurrent: LanguageServerHandle? = null
      val f = Fixture()
      f.read = { _, _ ->
        // The reconnect lands while the command is between its read and its send.
        wrapperCurrent = LanguageServerHandle(replacement, LanguageServerSession(), EPOCH + 1)
        stored
      }
      f.show()
      f.scheduler.advanceUntilIdle()
      (wrapperCurrent?.proxy === replacement) shouldBe true
      verify(exactly = 1) { f.proxy.pluginNotification(any()) }
      verify(exactly = 0) { replacement.pluginNotification(any()) }
    }

    it("(ii)+(iii) a snapshot replaced between the read and the re-check: no send, stale notice, no tab") {
      val first = snapshot(finding())
      val second = snapshot(finding()) // equal by value, but a different instance
      var reads = 0
      val f = Fixture(read = { _, _ -> if (reads++ == 0) first else second })
      f.show()
      f.scheduler.advanceUntilIdle()
      reads shouldBe 2
      f.sent().shouldBeEmpty()
      f.notified shouldContainExactly listOf(STALE_TEXT)
      f.opened shouldBe 0
    }

    it("(ii) a snapshot gone at the re-check aborts the same way") {
      var reads = 0
      val f = Fixture(read = { _, _ -> if (reads++ == 0) snapshot(finding()) else null })
      f.show()
      f.scheduler.advanceUntilIdle()
      f.sent().shouldBeEmpty()
      f.notified shouldContainExactly listOf(STALE_TEXT)
      f.opened shouldBe 0
    }
  }

  describe("what happens under the outbound lock (A24)") {
    it("projects outside the lock, re-checks and sends inside it, and hops to the UI thread after it") {
      val stored = snapshot(finding())
      val f = Fixture(read = { _, _ -> stored })
      val lockedAtRead = mutableListOf<Boolean>()
      var lockedAtProject: Boolean? = null
      var lockedAtSend: Boolean? = null
      var lockedAtHop: Boolean? = null
      f.read = { _, _ ->
        lockedAtRead += f.lock.isLocked
        stored
      }
      f.project = {
        lockedAtProject = f.lock.isLocked
        VulnerabilityProjection.of(it)
      }
      every { f.proxy.pluginNotification(any()) } answers { lockedAtSend = f.lock.isLocked }
      f.onUiThread = {
        lockedAtHop = f.lock.isLocked
        it.run()
      }
      f.show()
      f.scheduler.advanceUntilIdle()
      lockedAtRead shouldContainExactly listOf(false, true)
      lockedAtProject shouldBe false
      lockedAtSend shouldBe true
      lockedAtHop shouldBe false
      f.lock.isLocked shouldBe false
      f.opened shouldBe 1
    }
  }

  describe("the shared scope survives every failure (A23)") {
    it("read throws") {
      val f = Fixture(read = { _, _ -> throw IllegalStateException(MARKER) })
      f.show()
      f.scheduler.advanceUntilIdle()
      f.scopeStillRuns() shouldBe true
      f.sent().shouldBeEmpty()
      f.opened shouldBe 0
    }

    it("project throws") {
      val f = Fixture(read = { _, _ -> snapshot(finding()) }, project = { throw IllegalArgumentException(MARKER) })
      f.show()
      f.scheduler.advanceUntilIdle()
      f.scopeStillRuns() shouldBe true
      f.sent().shouldBeEmpty()
      f.opened shouldBe 0
    }

    it("the proxy throws: nothing opens, and the lock is released") {
      val stored = snapshot(finding())
      val f = Fixture(read = { _, _ -> stored })
      every { f.proxy.pluginNotification(any()) } throws IllegalStateException(MARKER)
      f.show()
      f.scheduler.advanceUntilIdle()
      f.scopeStillRuns() shouldBe true
      f.lock.isLocked shouldBe false
      f.opened shouldBe 0
    }

    it("onUiThread throws") {
      val stored = snapshot(finding())
      val f = Fixture(read = { _, _ -> stored }, onUiThread = { throw IllegalStateException(MARKER) })
      f.show()
      f.scheduler.advanceUntilIdle()
      f.scopeStillRuns() shouldBe true
      f.opened shouldBe 0
    }

    it("openTab throws: contained in the runnable, and the user is told") {
      val stored = snapshot(finding())
      val f = Fixture(read = { _, _ -> stored })
      f.openTab = { throw IllegalStateException(MARKER) }
      var escaped: Throwable? = null
      f.onUiThread = { runnable -> runCatching { runnable.run() }.onFailure { escaped = it } }
      f.show()
      f.scheduler.advanceUntilIdle()
      escaped shouldBe null
      f.notified shouldContainExactly listOf(OPEN_FAILED_TEXT)
      f.scopeStillRuns() shouldBe true
    }

    it("an Error from openTab (such as SWTError) is contained too") {
      val stored = snapshot(finding())
      val f = Fixture(read = { _, _ -> stored })
      f.openTab = { throw LinkageError(MARKER) }
      var escaped: Throwable? = null
      f.onUiThread = { runnable -> runCatching { runnable.run() }.onFailure { escaped = it } }
      f.show()
      f.scheduler.advanceUntilIdle()
      escaped shouldBe null
      f.notified shouldContainExactly listOf(OPEN_FAILED_TEXT)
    }

    it("CancellationException from read is not swallowed: the job ends cancelled, the scope lives on") {
      val f = Fixture(read = { _, _ -> throw CancellationException("stop") })
      f.show()
      val job = f.parent.children.single()
      f.scheduler.advanceUntilIdle()
      job.isCancelled shouldBe true
      f.notified.shouldBeEmpty()
      f.scopeStillRuns() shouldBe true
    }

    it("a completed failing job is not reported as cancelled") {
      val f = Fixture(read = { _, _ -> throw IllegalStateException(MARKER) })
      f.show()
      val job = f.parent.children.single()
      f.scheduler.advanceUntilIdle()
      job.isCancelled shouldBe false
      job.isCompleted shouldBe true
    }
  }

  describe("log hygiene (A7)") {
    val failures: List<Pair<String, (Fixture) -> Unit>> = listOf(
      "read" to { f -> f.read = { _, _ -> throw IllegalStateException("$MARKER $PATH") } },
      "project" to { f -> f.project = { throw IllegalArgumentException("$MARKER $it") } },
      "proxy" to { f ->
        every { f.proxy.pluginNotification(any()) } answers {
          throw IllegalStateException("$MARKER ${firstArg<Any>()}")
        }
      },
      "onUiThread" to { f -> f.onUiThread = { throw IllegalStateException("$MARKER $PATH") } },
      "openTab" to { f -> f.openTab = { throw IllegalStateException("$MARKER $PATH") } },
    )
    failures.forEach { (where, arrange) ->
      it("a failure in $where logs only the exception class name") {
        val logged = captureLog()
        val stored = snapshot(finding())
        val f = Fixture(read = { _, _ -> stored })
        arrange(f)
        f.show()
        f.scheduler.advanceUntilIdle()
        logged.size shouldBe 1
        logged.single() shouldStartWith "Failed to"
        logged.single() shouldNotContain MARKER
        logged.single() shouldNotContain "App.kt"
        val className = logged.single().substringAfterLast(": ")
        (className in setOf("IllegalStateException", "IllegalArgumentException")) shouldBe true
      }
    }

    it("the no-finding and stale paths log nothing at all") {
      val logged = captureLog()
      val f = Fixture(read = { _, _ -> null })
      f.show()
      f.scheduler.advanceUntilIdle()
      var reads = 0
      val g = Fixture(read = { _, _ -> if (reads++ == 0) snapshot(finding()) else snapshot(finding()) })
      g.show()
      g.scheduler.advanceUntilIdle()
      logged.shouldBeEmpty()
    }
  }
})
