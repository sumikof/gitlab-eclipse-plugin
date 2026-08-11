package com.gitlab.eclipse.ci.lint

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.UnstableConnectionException
import com.gitlab.eclipse.api.model.CiLintResult
import com.gitlab.eclipse.ci.actions.WriteOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.http.HttpTimeoutException

class CiLintTest : DescribeSpec({
  fun conn(url: String) = ConnectionSnapshot(url, "tok-secret", "fp", 1L)

  describe("runCiLint") {
    it("captures then gates then lints exactly once, returning Linted when the gate passes") {
      var lintCalls = 0
      val expected = CiLintResult(valid = true, mergedYaml = "merged: true", errors = emptyList())
      val result = runCiLint(
        contextInstanceUrl = "https://gl.example.com",
        capture = { conn("https://gl.example.com") },
        lint = {
          lintCalls++
          expected
        },
      )
      lintCalls shouldBe 1
      result shouldBe CiLintOutcome.Linted(expected)
    }

    it("does NOT call lint when capture returns null because the url was rejected (InstanceMismatch)") {
      var lintCalls = 0
      val result = runCiLint(
        contextInstanceUrl = "https://a.example.com",
        capture = { null },
        lint = {
          lintCalls++
          CiLintResult(valid = true, mergedYaml = null, errors = emptyList())
        },
      )
      lintCalls shouldBe 0
      result shouldBe CiLintOutcome.InstanceMismatch
    }

    // Safety boundary (AC-3a): capture is bound by the CALLER, so a mis-bound predicate can hand
    // back a NON-NULL snapshot from another instance. The in-function postcondition must stop it.
    it("does NOT call lint when a NON-null snapshot's url differs from the context (InstanceMismatch)") {
      var lintCalls = 0
      val result = runCiLint(
        contextInstanceUrl = "https://a.example.com",
        capture = { conn("https://b.example.com") },
        lint = {
          lintCalls++
          CiLintResult(valid = true, mergedYaml = null, errors = emptyList())
        },
      )
      lintCalls shouldBe 0
      result shouldBe CiLintOutcome.InstanceMismatch
    }

    it("treats a trailing slash difference as the same instance") {
      var lintCalls = 0
      val expected = CiLintResult(valid = true, mergedYaml = null, errors = emptyList())
      val result = runCiLint(
        contextInstanceUrl = "https://a/",
        capture = { conn("https://a") },
        lint = {
          lintCalls++
          expected
        },
      )
      lintCalls shouldBe 1
      result shouldBe CiLintOutcome.Linted(expected)
    }

    it("does NOT call lint when the connection is unstable (returns ConnectionUnstable)") {
      var lintCalls = 0
      val result = runCiLint(
        contextInstanceUrl = "https://gl.example.com",
        capture = { throw UnstableConnectionException() },
        lint = {
          lintCalls++
          CiLintResult(valid = true, mergedYaml = null, errors = emptyList())
        },
      )
      lintCalls shouldBe 0
      result shouldBe CiLintOutcome.ConnectionUnstable
      // Unstable stays distinguishable from the null (url-rejected) case: distinct audit reasons.
      result shouldNotBe CiLintOutcome.InstanceMismatch
    }

    it("maps a GitLabApiException from lint to Failed(http)") {
      val result = runCiLint(
        contextInstanceUrl = "https://gl.example.com",
        capture = { conn("https://gl.example.com") },
        lint = { throw GitLabApiException(400, "bad-body", "corr-400") },
      )
      result shouldBe CiLintOutcome.Failed(WriteOutcome.Failure(400, "corr-400", "http"))
    }

    it("maps HttpTimeoutException from lint to Failed(timeout, null, null)") {
      val result = runCiLint(
        contextInstanceUrl = "https://gl.example.com",
        capture = { conn("https://gl.example.com") },
        lint = { throw HttpTimeoutException("timed out") },
      )
      result shouldBe CiLintOutcome.Failed(WriteOutcome.Failure(null, null, "timeout"))
    }

    it("maps IOException from lint to Failed(io, null, null)") {
      val result = runCiLint(
        contextInstanceUrl = "https://gl.example.com",
        capture = { conn("https://gl.example.com") },
        lint = { throw IOException("connection reset") },
      )
      result shouldBe CiLintOutcome.Failed(WriteOutcome.Failure(null, null, "io"))
    }

    it("rethrows CancellationException from lint instead of classifying it") {
      shouldThrow<CancellationException> {
        runCiLint(
          contextInstanceUrl = "https://gl.example.com",
          capture = { conn("https://gl.example.com") },
          lint = { throw CancellationException("cancelled") },
        )
      }
    }
  }

  describe("buildCiLintAuditMessage") {
    it(
      "for Linted(valid=true, mergedYaml present) includes success/valid/merged=present " +
        "and never the yaml body or httpStatus",
    ) {
      val outcome = CiLintOutcome.Linted(
        CiLintResult(valid = true, mergedYaml = "yaml-body-marker", errors = emptyList()),
      )
      val msg = buildCiLintAuditMessage("https://gl.example.com/", "group%2Fp", "validateCiConfig", outcome)

      msg shouldContain "ciLint command=validateCiConfig"
      msg shouldContain "instanceUrl=https://gl.example.com"
      msg shouldContain "projectId=group%2Fp"
      msg shouldContain "outcome=success valid=true merged=present"
      msg shouldNotContain "httpStatus"
      msg shouldNotContain "yaml-body-marker"
    }

    it("for Linted(valid=false, mergedYaml absent, errors) includes merged=absent and never the errors body") {
      val outcome = CiLintOutcome.Linted(
        CiLintResult(valid = false, mergedYaml = null, errors = listOf("secret-ish")),
      )
      val msg = buildCiLintAuditMessage("https://gl.example.com", "p", "showMergedCiConfig", outcome)

      msg shouldContain "outcome=success valid=false merged=absent"
      msg shouldNotContain "secret-ish"
    }

    it("for Failed includes failureKind/httpStatus/correlationId") {
      val outcome = CiLintOutcome.Failed(WriteOutcome.Failure(403, "corr-403", "http"))
      val msg = buildCiLintAuditMessage("https://gl.example.com", "p", "validateCiConfig", outcome)

      msg shouldContain "outcome=failure failureKind=http"
      msg shouldContain "httpStatus=403"
      msg shouldContain "correlationId=corr-403"
    }

    it("for ConnectionUnstable records the abort reason") {
      val msg = buildCiLintAuditMessage("https://gl", "p", "validateCiConfig", CiLintOutcome.ConnectionUnstable)
      msg shouldContain "outcome=aborted reason=connection-unstable"
    }

    it("for InstanceMismatch records the abort reason") {
      val msg = buildCiLintAuditMessage("https://gl", "p", "validateCiConfig", CiLintOutcome.InstanceMismatch)
      msg shouldContain "outcome=aborted reason=instance-mismatch"
    }

    it("never includes a token in any outcome's audit line") {
      val lintedMergedYamlMarker = "yaml-body-token-sweep-marker"
      val outcomes = listOf(
        CiLintOutcome.Linted(CiLintResult(valid = true, mergedYaml = lintedMergedYamlMarker, errors = emptyList())),
        CiLintOutcome.Failed(WriteOutcome.Failure(403, "corr-403", "http")),
        CiLintOutcome.ConnectionUnstable,
        CiLintOutcome.InstanceMismatch,
      )
      outcomes.forEach { outcome ->
        val msg = buildCiLintAuditMessage("https://gl.example.com/", "group%2Fp", "validateCiConfig", outcome)
        msg shouldNotContain "tok-secret"
        msg shouldContain "instanceUrl=https://gl.example.com"
        msg shouldContain "projectId=group%2Fp"
        msg shouldContain "command=validateCiConfig"
        if (outcome is CiLintOutcome.Linted) {
          msg shouldNotContain lintedMergedYamlMarker
        }
      }
    }
  }
})
