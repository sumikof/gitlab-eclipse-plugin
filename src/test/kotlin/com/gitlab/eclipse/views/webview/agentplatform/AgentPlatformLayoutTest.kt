package com.gitlab.eclipse.views.webview.agentplatform

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class AgentPlatformLayoutTest : DescribeSpec({
  describe("fromPreference") {
    it("maps the two known values exactly") {
      AgentPlatformLayout.fromPreference("new") shouldBe AgentPlatformLayout.NEW
      AgentPlatformLayout.fromPreference("classic") shouldBe AgentPlatformLayout.CLASSIC
    }

    it("is an exact match only: other casing falls back to NEW") {
      AgentPlatformLayout.fromPreference("CLASSIC") shouldBe AgentPlatformLayout.NEW
    }

    it("falls back to NEW for blank, null and unknown values") {
      AgentPlatformLayout.fromPreference("") shouldBe AgentPlatformLayout.NEW
      AgentPlatformLayout.fromPreference(null) shouldBe AgentPlatformLayout.NEW
      AgentPlatformLayout.fromPreference("x") shouldBe AgentPlatformLayout.NEW
    }
  }

  describe("webview ids") {
    it("equal the published constants and the VS Code ids") {
      AgentPlatformLayout.NEW.webviewId shouldBe AgentPlatformLayout.NEW_WEBVIEW_ID
      AgentPlatformLayout.CLASSIC.webviewId shouldBe AgentPlatformLayout.CLASSIC_WEBVIEW_ID
      AgentPlatformLayout.NEW_WEBVIEW_ID shouldBe "root/duoAgentPlatform"
      AgentPlatformLayout.CLASSIC_WEBVIEW_ID shouldBe "agentic-tabs"
    }

    it("round-trips the preference value") {
      AgentPlatformLayout.entries.forEach { AgentPlatformLayout.fromPreference(it.preferenceValue) shouldBe it }
    }
  }
})
