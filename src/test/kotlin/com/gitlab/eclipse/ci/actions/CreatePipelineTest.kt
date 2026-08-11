package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.PostResult
import com.gitlab.eclipse.api.UnstableConnectionException
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class CreatePipelineTest : DescribeSpec({
  fun conn(url: String) = ConnectionSnapshot(url, "tok-secret", "fp", 1L)

  describe("sameConfiguredInstance") {
    it("matches ignoring a trailing slash difference") {
      sameConfiguredInstance("https://gl.example.com/", "https://gl.example.com") shouldBe true
    }
    it("rejects a different instance") {
      sameConfiguredInstance("https://gl.example.com", "https://other.example.com") shouldBe false
    }
  }

  describe("CreateWriteKey") {
    it("distinguishes different (project, ref) so they do not collide in the guard") {
      val a = CreateWriteKey("https://gl", "group%2Fp", "main")
      val b = CreateWriteKey("https://gl", "group%2Fp", "dev")
      val c = CreateWriteKey("https://gl", "group%2Fq", "main")
      (a == b) shouldBe false
      (a == c) shouldBe false
      a shouldBe CreateWriteKey("https://gl", "group%2Fp", "main")
    }
  }

  describe("runCreatePipeline") {
    it("does NOT call create when capture returns null because the url was rejected (InstanceMismatch)") {
      var createCalls = 0
      val result = runCreatePipeline(
        contextInstanceUrl = "https://gl.example.com",
        capture = { null },
        create = {
          createCalls++
          PostResult(201, null)
        },
      )
      createCalls shouldBe 0
      result shouldBe CreateResult.InstanceMismatch
    }

    // Safety boundary (AC-3a): capture is bound by the CALLER, so a mis-bound predicate can hand
    // back a NON-NULL snapshot from another instance. The in-function postcondition must stop it.
    it("does NOT call create when a NON-null snapshot's url differs from the context (InstanceMismatch)") {
      var createCalls = 0
      val result = runCreatePipeline(
        contextInstanceUrl = "https://gl.example.com",
        capture = { conn("https://other.example.com") },
        create = {
          createCalls++
          PostResult(201, null)
        },
      )
      createCalls shouldBe 0
      result shouldBe CreateResult.InstanceMismatch
    }

    it("calls create exactly once and returns Created when the gate passes") {
      var createCalls = 0
      val result = runCreatePipeline(
        contextInstanceUrl = "https://gl.example.com/",
        capture = { conn("https://gl.example.com") },
        create = {
          createCalls++
          PostResult(201, "cid")
        },
      )
      createCalls shouldBe 1
      result shouldBe CreateResult.Created(PostResult(201, "cid"))
    }

    it("does NOT call create when the connection is unstable (returns ConnectionUnstable)") {
      var createCalls = 0
      val result = runCreatePipeline(
        contextInstanceUrl = "https://gl.example.com",
        capture = { throw UnstableConnectionException() },
        create = {
          createCalls++
          PostResult(201, null)
        },
      )
      createCalls shouldBe 0
      result shouldBe CreateResult.ConnectionUnstable
      // Unstable stays distinguishable from the null (url-rejected) case: distinct audit reasons.
      result shouldNotBe CreateResult.InstanceMismatch
    }

    it("maps a GitLabApiException from create to Failed(http)") {
      val result = runCreatePipeline(
        contextInstanceUrl = "https://gl.example.com",
        capture = { conn("https://gl.example.com") },
        create = { throw GitLabApiException(403, "forbidden-body", "cid-403") },
      )
      result shouldBe CreateResult.Failed(WriteOutcome.Failure(403, "cid-403", "http"))
    }
  }

  describe("buildCreateAuditMessage") {
    it("includes action/instance/project/ref and never the token or body") {
      val msg = buildCreateAuditMessage(
        "https://gl.example.com/",
        "group%2Fp",
        "main",
        CreateResult.Failed(WriteOutcome.Failure(403, "cid-403", "http")),
      )
      msg shouldContain "action=create"
      msg shouldContain "instanceUrl=https://gl.example.com"
      msg shouldContain "projectId=group%2Fp"
      msg shouldContain "ref=main"
      msg shouldContain "httpStatus=403"
      msg shouldNotContain "tok-secret"
      msg shouldNotContain "forbidden-body"
    }
    it("records the abort reason for InstanceMismatch") {
      buildCreateAuditMessage("https://gl", "p", "main", CreateResult.InstanceMismatch) shouldContain "reason=instance-mismatch"
    }
    it("records outcome/status/correlationId and never the token for Created") {
      val msg = buildCreateAuditMessage(
        "https://gl.example.com/",
        "group%2Fp",
        "main",
        CreateResult.Created(PostResult(201, "cid-ok")),
      )
      msg shouldContain "outcome=success"
      msg shouldContain "httpStatus=201"
      msg shouldContain "correlationId=cid-ok"
      msg shouldNotContain "tok-secret"
    }
  }
})
