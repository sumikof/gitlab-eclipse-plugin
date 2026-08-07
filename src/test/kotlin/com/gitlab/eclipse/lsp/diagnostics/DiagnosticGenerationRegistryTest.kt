package com.gitlab.eclipse.lsp.diagnostics

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private const val SEC = "gitlab_security_scan"
private const val OTHER = "other_source"

class DiagnosticGenerationRegistryTest : DescribeSpec({
  beforeEach { DiagnosticGenerationRegistry.resetForTest() }
  afterEach { DiagnosticGenerationRegistry.resetForTest() }

  describe("generation and epoch are separate axes") {
    it("issues a globally unique generation that changes on every call") {
      val e = DiagnosticGenerationRegistry.currentEpoch
      val g1 = DiagnosticGenerationRegistry.nextGeneration("/a", e)!!
      val g2 = DiagnosticGenerationRegistry.nextGeneration("/b", e)!!
      val g3 = DiagnosticGenerationRegistry.nextGeneration("/a", e)!!
      (g1 != g2 && g2 != g3 && g1 != g3) shouldBe true
    }
    it("does not advance the epoch when generations are issued") {
      val e = DiagnosticGenerationRegistry.currentEpoch
      DiagnosticGenerationRegistry.nextGeneration("/a", e)
      DiagnosticGenerationRegistry.currentEpoch shouldBe e
    }
  }

  describe("shouldApply") {
    it("rejects a superseded generation for the same key") {
      val e = DiagnosticGenerationRegistry.currentEpoch
      val old = DiagnosticGenerationRegistry.nextGeneration("/a", e)!!
      val new = DiagnosticGenerationRegistry.nextGeneration("/a", e)!!
      DiagnosticGenerationRegistry.shouldApply("/a", new, e) shouldBe true
      DiagnosticGenerationRegistry.shouldApply("/a", old, e) shouldBe false
    }
    it("rejects a mismatched epoch") {
      val e = DiagnosticGenerationRegistry.currentEpoch
      val g = DiagnosticGenerationRegistry.nextGeneration("/a", e)!!
      DiagnosticGenerationRegistry.onServerStopped()
      DiagnosticGenerationRegistry.shouldApply("/a", g, e) shouldBe false
    }
    it("rejects everything while inactive") {
      val e = DiagnosticGenerationRegistry.currentEpoch
      val g = DiagnosticGenerationRegistry.nextGeneration("/a", e)!!
      DiagnosticGenerationRegistry.onDeactivate()
      DiagnosticGenerationRegistry.shouldApply("/a", g, e) shouldBe false
    }
  }

  describe("connection epoch is checked inside the same lock region") {
    it("refuses to issue a generation for a stale connection epoch") {
      val stale = DiagnosticGenerationRegistry.currentEpoch
      DiagnosticGenerationRegistry.onServerStopped()
      DiagnosticGenerationRegistry.nextGeneration("/a", stale) shouldBe null
    }
    it("refuses to issue a source token for a stale connection epoch") {
      val stale = DiagnosticGenerationRegistry.currentEpoch
      DiagnosticGenerationRegistry.onServerStopped()
      DiagnosticGenerationRegistry.acceptToken(SEC, stale) shouldBe null
    }
  }

  describe("onServerStopped") {
    it("advances the epoch but leaves active untouched") {
      DiagnosticGenerationRegistry.onServerStopped()
      DiagnosticGenerationRegistry.active shouldBe true
    }
    it("is idempotent for the same connection") {
      val before = DiagnosticGenerationRegistry.currentEpoch
      DiagnosticGenerationRegistry.onServerStopped()
      val once = DiagnosticGenerationRegistry.currentEpoch
      DiagnosticGenerationRegistry.onServerStopped()
      DiagnosticGenerationRegistry.currentEpoch shouldBe once
      (once > before) shouldBe true
    }
    it("advances again for the next connection, once onServerStarted has said there is one") {
      val before = DiagnosticGenerationRegistry.currentEpoch
      DiagnosticGenerationRegistry.onServerStopped()

      DiagnosticGenerationRegistry.onServerStarted()
      DiagnosticGenerationRegistry.onServerStopped()

      // Without the re-arm the second stop is a silent no-op: the connection that just died and the
      // one that replaced it would share an epoch, so a late response from the dead one would be
      // accepted and `deleteMarkersNotInEpoch(currentEpoch)` would keep the markers it must remove.
      DiagnosticGenerationRegistry.currentEpoch shouldBe before + 2
    }
    it("does not move the epoch when a connection starts") {
      val before = DiagnosticGenerationRegistry.currentEpoch
      DiagnosticGenerationRegistry.onServerStarted()
      DiagnosticGenerationRegistry.currentEpoch shouldBe before
    }
  }

  describe("onActivate") {
    it("advances the epoch and clears latest, so a generation issued before it no longer applies") {
      val e = DiagnosticGenerationRegistry.currentEpoch
      val g = DiagnosticGenerationRegistry.nextGeneration("/a", e)!!
      DiagnosticGenerationRegistry.shouldApply("/a", g, e) shouldBe true

      DiagnosticGenerationRegistry.onActivate()

      (DiagnosticGenerationRegistry.currentEpoch > e) shouldBe true
      // stale epoch alone would already fail shouldApply; assert with the *new* epoch too,
      // to prove `latest` was actually cleared and not just the epoch mismatching.
      val newEpoch = DiagnosticGenerationRegistry.currentEpoch
      DiagnosticGenerationRegistry.shouldApply("/a", g, newEpoch) shouldBe false
    }
  }

  describe("currentGenerationCounter") {
    it("reflects the number of generations actually issued, not a constant") {
      DiagnosticGenerationRegistry.currentGenerationCounter() shouldBe 0L
      val e = DiagnosticGenerationRegistry.currentEpoch
      DiagnosticGenerationRegistry.nextGeneration("/a", e)
      DiagnosticGenerationRegistry.currentGenerationCounter() shouldBe 1L
      DiagnosticGenerationRegistry.nextGeneration("/b", e)
      DiagnosticGenerationRegistry.currentGenerationCounter() shouldBe 2L
    }
  }

  describe("isLatestSettingsSeq") {
    it("is true for the newest issued seq and false once a newer one is issued") {
      val seq = DiagnosticGenerationRegistry.nextSettingsSeq()
      DiagnosticGenerationRegistry.isLatestSettingsSeq(seq) shouldBe true
      val newer = DiagnosticGenerationRegistry.nextSettingsSeq()
      DiagnosticGenerationRegistry.isLatestSettingsSeq(seq) shouldBe false
      DiagnosticGenerationRegistry.isLatestSettingsSeq(newer) shouldBe true
    }
  }

  describe("source suspension is scoped and atomic") {
    it("excludes only the suspended source") {
      val e = DiagnosticGenerationRegistry.currentEpoch
      DiagnosticGenerationRegistry.suspendSource(SEC, DiagnosticGenerationRegistry.nextSettingsSeq())
      DiagnosticGenerationRegistry.acceptToken(SEC, e) shouldBe null
      DiagnosticGenerationRegistry.acceptToken(OTHER, e).shouldNotBeNull()
    }
    it("invalidates a token captured before the suspension") {
      val e = DiagnosticGenerationRegistry.currentEpoch
      val token = DiagnosticGenerationRegistry.acceptToken(SEC, e)
      DiagnosticGenerationRegistry.suspendSource(SEC, DiagnosticGenerationRegistry.nextSettingsSeq())
      DiagnosticGenerationRegistry.isTokenValid(token) shouldBe false
    }
    it("invalidates a token across suspend and resume") {
      val e = DiagnosticGenerationRegistry.currentEpoch
      val token = DiagnosticGenerationRegistry.acceptToken(SEC, e)
      DiagnosticGenerationRegistry.suspendSource(SEC, DiagnosticGenerationRegistry.nextSettingsSeq())
      DiagnosticGenerationRegistry.resumeSource(SEC, DiagnosticGenerationRegistry.nextSettingsSeq())
      DiagnosticGenerationRegistry.isTokenValid(token) shouldBe false
    }
    it("is idempotent: a second suspend must not re-enable the source") {
      val e = DiagnosticGenerationRegistry.currentEpoch
      DiagnosticGenerationRegistry.suspendSource(SEC, DiagnosticGenerationRegistry.nextSettingsSeq())
      DiagnosticGenerationRegistry.suspendSource(SEC, DiagnosticGenerationRegistry.nextSettingsSeq())
      DiagnosticGenerationRegistry.acceptToken(SEC, e) shouldBe null
    }
    it("is idempotent: a second resume must not suspend the source") {
      val e = DiagnosticGenerationRegistry.currentEpoch
      DiagnosticGenerationRegistry.resumeSource(SEC, DiagnosticGenerationRegistry.nextSettingsSeq())
      DiagnosticGenerationRegistry.resumeSource(SEC, DiagnosticGenerationRegistry.nextSettingsSeq())
      DiagnosticGenerationRegistry.acceptToken(SEC, e).shouldNotBeNull()
    }
  }

  describe("settings transition ordering") {
    it("applies the first transition") {
      DiagnosticGenerationRegistry.suspendSource(SEC, DiagnosticGenerationRegistry.nextSettingsSeq()) shouldBe true
    }
    it("drops a transition that was overtaken in the queue") {
      val older = DiagnosticGenerationRegistry.nextSettingsSeq()
      val newer = DiagnosticGenerationRegistry.nextSettingsSeq()
      DiagnosticGenerationRegistry.resumeSource(SEC, newer) shouldBe true
      DiagnosticGenerationRegistry.suspendSource(SEC, older) shouldBe false
      DiagnosticGenerationRegistry.isSuspended(SEC) shouldBe false
    }
    it("drops a transition a newer one has overtaken but not yet applied") {
      // The user toggled the setting twice and the older coroutine reached the registry first.
      // "Newer already applied" is not the only way to be stale: nothing has applied yet here, so a
      // guard that only compares against the last *applied* seq lets this one through and flips the
      // parity. The caller's own latest-check discards the destructive half afterwards, but by then
      // every scan and every response has already seen the wrong parity.
      val older = DiagnosticGenerationRegistry.nextSettingsSeq()
      DiagnosticGenerationRegistry.nextSettingsSeq()

      DiagnosticGenerationRegistry.suspendSource(SEC, older) shouldBe false

      DiagnosticGenerationRegistry.isSuspended(SEC) shouldBe false
    }
  }

  describe("reconcileSource") {
    it("aligns the parity to the current setting") {
      DiagnosticGenerationRegistry.suspendSource(SEC, DiagnosticGenerationRegistry.nextSettingsSeq())
      DiagnosticGenerationRegistry.reconcileSource(SEC, desiredSuspended = false)
      DiagnosticGenerationRegistry.isSuspended(SEC) shouldBe false
    }
    it("does not consume the settings sequence, so a pending transition still applies") {
      val pending = DiagnosticGenerationRegistry.nextSettingsSeq()
      DiagnosticGenerationRegistry.reconcileSource(SEC, desiredSuspended = false)
      DiagnosticGenerationRegistry.suspendSource(SEC, pending) shouldBe true
    }
  }
})
