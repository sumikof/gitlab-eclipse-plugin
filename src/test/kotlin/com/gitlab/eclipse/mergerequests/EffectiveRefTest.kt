package com.gitlab.eclipse.mergerequests

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

private const val REMOTE = "origin"

class EffectiveRefTest : DescribeSpec({
  fun branch(
    name: String? = "feature",
    trackingBranch: String? = null,
    hasUpstream: Boolean = trackingBranch != null,
    upstreamRemote: String? = null,
    headSha: String? = "abc123",
  ) = CurrentBranch(name, trackingBranch, hasUpstream, upstreamRemote, headSha)

  describe("EffectiveRef.resolve") {
    it("uses the tracking branch when its upstream remote matches the given remote") {
      val branch = branch(name = "feature", trackingBranch = "upstream-name", upstreamRemote = REMOTE)

      EffectiveRef.resolve(branch, REMOTE) shouldBe "upstream-name"
    }

    it("falls back to the local name when the upstream remote does not match") {
      val branch = branch(name = "feature", trackingBranch = "upstream-name", upstreamRemote = "fork")

      EffectiveRef.resolve(branch, REMOTE) shouldBe "feature"
    }

    it("falls back to the local name when there is no tracking branch") {
      val branch = branch(name = "feature", trackingBranch = null, upstreamRemote = null)

      EffectiveRef.resolve(branch, REMOTE) shouldBe "feature"
    }

    it("returns null for a detached HEAD (no local name, no tracking branch)") {
      val branch = branch(name = null, trackingBranch = null, upstreamRemote = null)

      EffectiveRef.resolve(branch, REMOTE) shouldBe null
    }

    it("falls back to the local name when tracking branch is set but its remote mismatches, name also set") {
      val branch = branch(name = "other-local-name", trackingBranch = "tracked", upstreamRemote = "fork")

      EffectiveRef.resolve(branch, REMOTE) shouldBe "other-local-name"
    }
  }
})
