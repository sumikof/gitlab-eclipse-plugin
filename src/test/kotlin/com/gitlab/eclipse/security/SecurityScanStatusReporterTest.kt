package com.gitlab.eclipse.security

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticUri
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.eclipse.core.resources.IFile
import org.eclipse.core.runtime.Path
import java.io.File

private const val PATH_A = "/w/a.kt"
private const val PATH_B = "/w/b.kt"

private const val AUTH_MESSAGE =
  "GitLab security scan failed: authentication failed. Your token may be invalid or expired. " +
    "Re-authenticate in the GitLab preferences."
private const val CANCELLED_MESSAGE =
  "GitLab security scan: the scan was cancelled because the language server restarted. " +
    "Run the scan again."

class SecurityScanStatusReporterTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  fun epoch() = DiagnosticGenerationRegistry.currentEpoch

  /** A response as it arrives once a scan really ran. */
  fun response(status: Int?, findings: Int? = null, error: String? = null) = SecurityScanResponse(
    filePath = PATH_A,
    status = status,
    results = findings?.let { count -> List(count) { "finding" } },
    error = error,
  )

  /** Settles [path] as a command by putting the waiter the command would have registered. */
  fun asCommand(path: String = PATH_A, response: SecurityScanResponse): ResponseDecision {
    CommandWaiters.add(path, epoch()) shouldNotBe null
    return SecurityScanStatusReporter.settle(path, response, epoch())
  }

  /** Settles [path] as a save: nothing is waiting, which is what makes it a save. */
  fun asSave(path: String = PATH_A, response: SecurityScanResponse): ResponseDecision =
    SecurityScanStatusReporter.settle(path, response, epoch())

  fun reportOf(decision: ResponseDecision) = decision as ResponseDecision.Report

  beforeEach {
    DiagnosticGenerationRegistry.resetForTest()
    CommandWaiters.resetForTest()
    SecurityScanStatusReporter.resetForTest()
  }

  afterEach {
    SecurityScanStatusReporter.resetForTest()
    CommandWaiters.resetForTest()
    DiagnosticGenerationRegistry.resetForTest()
  }

  describe("fixed messages") {
    it("maps known statuses to fixed client-side messages") {
      SecurityScanStatusReporter.messageForStatus(401) shouldBe AUTH_MESSAGE
      SecurityScanStatusReporter.messageForStatus(403) shouldBe
        "GitLab security scan failed: the real-time scan is not available for this project or namespace."
      SecurityScanStatusReporter.messageForStatus(404) shouldBe
        "GitLab security scan failed: the real-time scan is not available on this GitLab instance " +
        "(requires GitLab 17.5.0 or later)."
      SecurityScanStatusReporter.messageForStatus(500) shouldBe
        "GitLab security scan failed (status 500). See the Error Log for details."
      SecurityScanStatusReporter.messageForStatus(null) shouldBe
        "GitLab security scan failed (status -). See the Error Log for details."
    }

    it("never leaks the server-provided error body into the notification or the audit line") {
      val secret = "Bearer glpat-SECRET-TOKEN"

      val decision = SecurityScanStatusReporter.settle(
        PATH_A,
        SecurityScanResponse(filePath = PATH_A, status = 500, error = secret),
        epoch(),
      ) as ResponseDecision.Report

      decision.notify!!.contains(secret) shouldBe false
      decision.auditLine.contains(secret) shouldBe false
      decision.auditLine.contains("glpat") shouldBe false
    }

    it("keeps the server error out of every outcome, not only the one that is shown") {
      val secret = "token=glpat-SECRET"
      // A success and a suppressed save both build an audit line too, and neither may quote it.
      val success = reportOf(asSave(response = response(200, findings = 1, error = secret)))
      val firstFailure = reportOf(asSave(response = response(500, error = secret)))
      val repeatFailure = reportOf(asSave(response = response(500, error = secret)))

      listOf(success, firstFailure, repeatFailure).forEach { report ->
        report.auditLine.contains("glpat") shouldBe false
        (report.notify?.contains("glpat") ?: false) shouldBe false
      }
      repeatFailure.notify shouldBe null
    }
  }

  // Design §11.3. Every row of the table is one case here or, for the rows the launcher owns, one
  // case in SecurityScanLauncherTest; see the mapping in the task report.
  describe("notification policy") {
    it("row 1: tells a command how many issues were found") {
      val report = reportOf(asCommand(response = response(200, findings = 3)))

      report.notify shouldBe "GitLab security scan: 3 issue(s) found. See the Problems view."
      report.auditLine shouldBe
        "securityScan source=command outcome=success httpStatus=200 findings=3 exceptionType=- path=-"
    }

    it("row 2: tells a command when a clean scan found nothing") {
      val report = reportOf(asCommand(response = response(200, findings = 0)))

      report.notify shouldBe "GitLab security scan: no issues found."
      report.auditLine shouldBe
        "securityScan source=command outcome=success httpStatus=200 findings=0 exceptionType=- path=-"
    }

    it("row 3: tells a command about a failure with the fixed message for its status") {
      val report = reportOf(asCommand(response = response(401)))

      report.notify shouldBe AUTH_MESSAGE
      report.auditLine shouldBe
        "securityScan source=command outcome=failure httpStatus=401 findings=- exceptionType=- path=-"
    }

    it("row 5: stays silent about a successful save whatever it found") {
      reportOf(asSave(response = response(200, findings = 0))).notify shouldBe null
      reportOf(asSave(response = response(200, findings = 7))).notify shouldBe null
      reportOf(asSave(response = response(200, findings = 7))).auditLine shouldBe
        "securityScan source=save outcome=success httpStatus=200 findings=7 exceptionType=- path=-"
    }

    it("row 6: tells a save about a failure once and stays silent while nothing changes") {
      reportOf(asSave(response = response(403))).notify shouldBe
        "GitLab security scan failed: the real-time scan is not available for this project or namespace."

      // The same failure on the same file on every subsequent save. Saying it again on each
      // keystroke-triggered write would make the feature unusable.
      reportOf(asSave(response = response(403))).notify shouldBe null
      reportOf(asSave(response = response(403))).notify shouldBe null

      // Still audited every time, so the log shows how often it really happened.
      reportOf(asSave(response = response(403))).auditLine shouldBe
        "securityScan source=save outcome=failure httpStatus=403 findings=- exceptionType=- path=-"
    }

    it("row 6: suppresses per file, so another file still gets its own notification") {
      reportOf(asSave(response = response(500))).notify shouldNotBe null

      reportOf(asSave(PATH_B, response(500))).notify shouldNotBe null
    }

    it("row 10: tells a command that a restart cancelled its scan") {
      CommandWaiters.add(PATH_A, epoch())
      CommandWaiters.add(PATH_B, epoch())
      val dead = epoch()
      DiagnosticGenerationRegistry.onServerStopped()

      val report = SecurityScanStatusReporter.cancelPending(dead, ScanCancelReason.SERVER_STOPPED)

      report.notify shouldBe CANCELLED_MESSAGE
      report.auditLines shouldBe listOf(
        "securityScan source=command outcome=cancelled httpStatus=- findings=- exceptionType=- path=-",
        "securityScan source=command outcome=cancelled httpStatus=- findings=- exceptionType=- path=-",
      )
    }

    it("row 10: accounts for every cancelled request, not merely every file") {
      // Two commands queued on the same file are two requests that were thrown away. An audit
      // that showed one line would undercount what the restart actually destroyed.
      CommandWaiters.add(PATH_A, epoch())
      CommandWaiters.add(PATH_A, epoch())
      CommandWaiters.add(PATH_B, epoch())
      val dead = epoch()
      DiagnosticGenerationRegistry.onServerStopped()

      val report = SecurityScanStatusReporter.cancelPending(dead, ScanCancelReason.SERVER_STOPPED)

      report.auditLines.size shouldBe 3
      report.notify shouldBe CANCELLED_MESSAGE
    }

    it("row 11: says nothing about a save when the server stops, because a save has no waiter") {
      // Exactly what the launcher does for a save: it sends, and it registers nothing.
      val dead = epoch()
      DiagnosticGenerationRegistry.onServerStopped()

      val report = SecurityScanStatusReporter.cancelPending(dead, ScanCancelReason.SERVER_STOPPED)

      report.notify shouldBe null
      report.auditLines shouldBe emptyList()
    }

    it("audits a scan dropped by switching the feature off without telling the user again") {
      CommandWaiters.add(PATH_A, epoch())

      val report = SecurityScanStatusReporter.cancelPending(epoch(), ScanCancelReason.SETTING_DISABLED)

      report.notify shouldBe null
      report.auditLines shouldBe listOf(
        "securityScan source=command outcome=cancelled httpStatus=- findings=- exceptionType=- path=-"
      )
    }
  }

  // F6: a user who pressed a button always gets an answer. There is no input that makes a command
  // silent, so each branch that can decide "notify" is driven here with a command.
  describe("a command is never suppressed") {
    it("repeats the same failure as often as the user asks for it") {
      repeat(3) { reportOf(asCommand(response = response(500))).notify shouldNotBe null }
    }

    it("is not suppressed by a save that already reported the same failure") {
      reportOf(asSave(response = response(500))).notify shouldNotBe null
      reportOf(asSave(response = response(500))).notify shouldBe null

      reportOf(asCommand(response = response(500))).notify shouldNotBe null
    }

    it("is answered on every success too, however often it is repeated") {
      repeat(3) { reportOf(asCommand(response = response(200, findings = 0))).notify shouldNotBe null }
    }
  }

  // All four release conditions from §11.3. Missing one leaves a user who fixed the cause never
  // hearing about the file again.
  describe("save suppression is released") {
    it("when the status changes") {
      reportOf(asSave(response = response(500))).notify shouldNotBe null
      reportOf(asSave(response = response(500))).notify shouldBe null

      reportOf(asSave(response = response(401))).notify shouldBe AUTH_MESSAGE
    }

    it("when a success comes in between") {
      reportOf(asSave(response = response(500))).notify shouldNotBe null
      reportOf(asSave(response = response(200, findings = 0))).notify shouldBe null

      reportOf(asSave(response = response(500))).notify shouldNotBe null
    }

    it("when the success in between was a command's") {
      reportOf(asSave(response = response(500))).notify shouldNotBe null
      reportOf(asCommand(response = response(200, findings = 0))).notify shouldNotBe null

      reportOf(asSave(response = response(500))).notify shouldNotBe null
    }

    it("when the language server restarts") {
      reportOf(asSave(response = response(500))).notify shouldNotBe null
      reportOf(asSave(response = response(500))).notify shouldBe null
      val dead = epoch()
      DiagnosticGenerationRegistry.onServerStopped()

      SecurityScanStatusReporter.cancelPending(dead, ScanCancelReason.SERVER_STOPPED)

      reportOf(asSave(response = response(500))).notify shouldNotBe null
    }

    it("when scanning is switched back on") {
      reportOf(asSave(response = response(500))).notify shouldNotBe null
      reportOf(asSave(response = response(500))).notify shouldBe null

      SecurityScanStatusReporter.onScanningReenabled()

      reportOf(asSave(response = response(500))).notify shouldNotBe null
    }

    it("is not released by time alone") {
      // Deliberately no clock in the suppression state: a window that reopens on its own would
      // report a failure the user already fixed, and hide one that came back inside the window.
      reportOf(asSave(response = response(500))).notify shouldNotBe null
      repeat(50) { reportOf(asSave(response = response(500))).notify shouldBe null }
    }
  }

  describe("connection epoch") {
    it("rejects a response that belongs to a connection that is gone") {
      val dead = epoch()
      DiagnosticGenerationRegistry.onServerStopped()

      SecurityScanStatusReporter.settle(PATH_A, response(500), dead) shouldBe ResponseDecision.Rejected
    }

    it("leaves the live connection's waiter alone when a dead connection answers") {
      val dead = epoch()
      DiagnosticGenerationRegistry.onServerStopped()
      // The user started a new scan against the new connection.
      CommandWaiters.add(PATH_A, epoch()) shouldNotBe null

      SecurityScanStatusReporter.settle(PATH_A, response(200), dead) shouldBe ResponseDecision.Rejected

      CommandWaiters.consumeOldest(PATH_A, epoch()) shouldBe WaiterMatch.COMMAND
    }

    it("does not touch the save suppression state when it rejects") {
      // B already has a failure on record; A has nothing on record.
      reportOf(asSave(PATH_B, response(500))).notify shouldNotBe null
      val dead = epoch()
      DiagnosticGenerationRegistry.onServerStopped()

      // A rejected failure must not *record* suppression, and a rejected success must not
      // *release* it. Both are state changes a rejection has no business making.
      SecurityScanStatusReporter.settle(PATH_A, response(401), dead) shouldBe ResponseDecision.Rejected
      SecurityScanStatusReporter.settle(PATH_B, response(200), dead) shouldBe ResponseDecision.Rejected

      // Nothing was recorded for A, so its first live failure is still news.
      reportOf(asSave(PATH_A, response(401))).notify shouldNotBe null
      // Nothing was released for B, so its repeat is still suppressed.
      reportOf(asSave(PATH_B, response(500))).notify shouldBe null
    }
  }

  describe("audit line") {
    it("records a workspace relative path or nothing, never the absolute one") {
      // Headless: the workspace cannot answer, so every lookup is empty and the field is absent.
      // The assertion that matters is the second one, which holds either way.
      val report = reportOf(asCommand(response = response(200, findings = 1)))

      report.auditLine.endsWith(" path=-") shouldBe true
      report.auditLine.contains(PATH_A) shouldBe false
    }

    it("fills every absent field with a dash rather than dropping it") {
      val report = reportOf(asSave(response = SecurityScanResponse(filePath = PATH_A)))

      report.auditLine shouldBe
        "securityScan source=save outcome=failure httpStatus=- findings=- exceptionType=- path=-"
    }

    // Headless, the workspace throws on every lookup, so `path=-` is the only outcome the tests
    // above can observe. These drive the other branch through the injected resolver, which is the
    // only way the property that matters — a *resolved* path is the workspace relative one, never
    // the absolute input — can be proven at all.
    it("carries the workspace relative path, never the absolute one it was given") {
      val absolute = "/home/u/workspaces/ws/Project/src/a.kt"
      val file = mockk<IFile>()
      every { file.fullPath } returns Path("/Project/src/a.kt")

      val report = SecurityScanStatusReporter.settle(
        absolute,
        SecurityScanResponse(filePath = absolute, status = 500),
        epoch(),
      ) { listOf(file) } as ResponseDecision.Report

      report.auditLine shouldBe
        "securityScan source=save outcome=failure httpStatus=500 findings=- " +
        "exceptionType=- path=/Project/src/a.kt"
      report.auditLine.contains("/home/u") shouldBe false
      report.auditLine.contains(absolute) shouldBe false
    }

    it("takes the first file when the same location is linked into several projects") {
      val first = mockk<IFile>()
      val second = mockk<IFile>()
      every { first.fullPath } returns Path("/First/a.kt")
      every { second.fullPath } returns Path("/Second/a.kt")

      val report = SecurityScanStatusReporter.settle(
        "/abs/a.kt",
        SecurityScanResponse(filePath = "/abs/a.kt", status = 200),
        epoch(),
      ) { listOf(first, second) } as ResponseDecision.Report

      report.auditLine.endsWith(" path=/First/a.kt") shouldBe true
    }

    it("falls back to a dash rather than the absolute path when the lookup throws") {
      val report = SecurityScanStatusReporter.settle(
        "/abs/a.kt",
        SecurityScanResponse(filePath = "/abs/a.kt", status = 200),
        epoch(),
      ) { error("workspace is closed") } as ResponseDecision.Report

      report.auditLine.endsWith(" path=-") shouldBe true
      report.auditLine.contains("/abs") shouldBe false
    }

    it("keeps the findings themselves out of the record") {
      val report = reportOf(
        asSave(
          response = SecurityScanResponse(
            filePath = PATH_A,
            status = 200,
            results = listOf("CWE-89 SQL injection in a.kt"),
          )
        )
      )

      report.auditLine.contains("CWE") shouldBe false
      report.auditLine.contains("findings=1") shouldBe true
    }
  }

  describe("securityScanPathKey") {
    it("normalises the spelling the request used") {
      securityScanPathKey("file:/w/a.kt") shouldBe PATH_A
    }

    it("normalises the spelling vscode-uri produces") {
      // The language server echoes back its own spelling, and it is built on vscode-uri, which
      // writes the authority-less form. Prefixing `file:` onto this would make it opaque and the
      // waiter would never be found.
      securityScanPathKey("file:///w/a.kt") shouldBe PATH_A
    }

    it("accepts a bare absolute path") {
      securityScanPathKey(PATH_A) shouldBe PATH_A
    }

    it("accepts a bare Windows path and agrees with the request's own key") {
      securityScanPathKey("C:\\src\\a.kt") shouldBe "/C:/src/a.kt"
      securityScanPathKey("c:\\src\\a.kt") shouldBe "/C:/src/a.kt"
      securityScanPathKey("file:/c:/src/a.kt") shouldBe "/C:/src/a.kt"
    }

    it("returns something that cannot be normalised unchanged") {
      securityScanPathKey("untitled:Untitled-1") shouldBe "untitled:Untitled-1"
    }

    // A path is not a URI. Concatenating one makes `URI()` throw on a space and read a `#` as a
    // fragment separator, and either way the key stops matching the one the request registered —
    // so the command is released only by its sixty second deadline.
    it("accepts a path containing a space") {
      securityScanPathKey("/w/my docs/a.kt") shouldBe "/w/my docs/a.kt"
    }

    it("accepts a Windows path containing a space") {
      securityScanPathKey("C:\\My Docs\\a.kt") shouldBe "/C:/My Docs/a.kt"
    }

    it("keeps a UNC host in the path instead of losing it to the authority") {
      // `file://host/share/f.kt` would parse `host` as the authority and truncate the key to
      // `/share/f.kt`, silently merging two different servers' copies of the same share.
      securityScanPathKey("\\\\host\\share\\f.kt") shouldBe "//host/share/f.kt"
    }

    it("does not let a hash in the name truncate the key") {
      securityScanPathKey("/w/a#b.kt") shouldBe "/w/a#b.kt"
    }

    it("agrees with the key the request side registered") {
      // The waiter is registered under DiagnosticUri.normalize(<the uri we sent>); a response that
      // normalised to anything else would never find it. Driven through the real registry here
      // rather than asserted against a literal.
      listOf("/w/my docs/a.kt", "/w/a#b.kt", PATH_A).forEach { osPath ->
        val sentUri = File(osPath).toURI().toASCIIString()
        securityScanPathKey(osPath) shouldBe DiagnosticUri.normalize(sentUri)
      }
    }
  }
})
