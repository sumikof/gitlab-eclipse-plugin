package com.gitlab.eclipse.views.webview

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private const val WEBVIEW_URL = "http://127.0.0.1:39111/webview/security-vuln-details?_csrf=abc"
private const val OTHER_WEBVIEW_URL = "http://127.0.0.1:40222/webview/security-vuln-details?_csrf=def"

private fun openGuard() = TopLevelNavigationGuard().apply { expectLoad(WEBVIEW_URL) }

class TopLevelNavigationGuardTest : DescribeSpec({
  describe("TopLevelNavigationGuard before any expected load") {
    it("blocks a top-level navigation") {
      TopLevelNavigationGuard().allows(WEBVIEW_URL, topLevel = true) shouldBe false
    }

    it("allows a sub-frame navigation") {
      TopLevelNavigationGuard().allows("https://example.com/", topLevel = false) shouldBe true
    }
  }

  describe("TopLevelNavigationGuard while the initial load is expected") {
    it("allows the exact expected url") {
      openGuard().allows(WEBVIEW_URL, topLevel = true) shouldBe true
    }

    it("allows the server's redirect to the trailing-slash variant") {
      openGuard().allows(
        "http://127.0.0.1:39111/webview/security-vuln-details/?_csrf=abc",
        topLevel = true,
      ) shouldBe true
    }

    it("ignores the query and fragment when matching") {
      openGuard().allows("http://127.0.0.1:39111/webview/security-vuln-details#top", topLevel = true) shouldBe true
    }

    it("blocks the same path on another port") {
      openGuard().allows(
        "http://127.0.0.1:39112/webview/security-vuln-details?_csrf=abc",
        topLevel = true,
      ) shouldBe false
    }

    it("blocks the same path on another host") {
      openGuard().allows(
        "http://example.com:39111/webview/security-vuln-details?_csrf=abc",
        topLevel = true,
      ) shouldBe false
    }

    it("blocks the same path under another scheme") {
      openGuard().allows(
        "https://127.0.0.1:39111/webview/security-vuln-details?_csrf=abc",
        topLevel = true,
      ) shouldBe false
    }

    it("blocks another path on the same origin") {
      openGuard().allows("http://127.0.0.1:39111/webview/root/mcp?_csrf=abc", topLevel = true) shouldBe false
    }

    it("blocks a path that only starts with the expected one") {
      openGuard().allows(
        "http://127.0.0.1:39111/webview/security-vuln-details/evil?_csrf=abc",
        topLevel = true,
      ) shouldBe false
    }

    it("blocks an external https url") {
      openGuard().allows("https://gitlab.com/some/project", topLevel = true) shouldBe false
    }

    it("blocks about:blank") {
      openGuard().allows("about:blank", topLevel = true) shouldBe false
    }

    it("blocks a javascript: url") {
      openGuard().allows("javascript:alert(1)", topLevel = true) shouldBe false
    }

    it("blocks a null location") {
      openGuard().allows(null, topLevel = true) shouldBe false
    }

    it("blocks an unparseable location") {
      openGuard().allows("http://127.0.0.1:39111/web view/%zz", topLevel = true) shouldBe false
    }

    it("allows a sub-frame navigation") {
      openGuard().allows("https://example.com/", topLevel = false) shouldBe true
    }
  }

  describe("TopLevelNavigationGuard after the load completed") {
    val completed = { openGuard().apply { loadCompleted() } }

    it("blocks the same url") {
      completed().allows(WEBVIEW_URL, topLevel = true) shouldBe false
    }

    it("blocks a fragment-only change") {
      completed().allows("$WEBVIEW_URL#section", topLevel = true) shouldBe false
    }

    it("blocks an http url") {
      completed().allows("http://example.com/", topLevel = true) shouldBe false
    }

    it("blocks an https url") {
      completed().allows("https://gitlab.com/some/project", topLevel = true) shouldBe false
    }

    it("still allows a sub-frame navigation") {
      completed().allows("https://example.com/", topLevel = false) shouldBe true
    }
  }

  describe("TopLevelNavigationGuard on a second expected load") {
    it("re-opens the window for the new url after a completed load") {
      val guard = openGuard().apply {
        loadCompleted()
        expectLoad(OTHER_WEBVIEW_URL)
      }

      guard.allows(OTHER_WEBVIEW_URL, topLevel = true) shouldBe true
    }

    it("no longer treats the old url as special") {
      val guard = openGuard().apply { expectLoad(OTHER_WEBVIEW_URL) }

      guard.allows(WEBVIEW_URL, topLevel = true) shouldBe false
    }
  }

  describe("navigationGuardFor") {
    it("guards the vulnerability details webview") {
      navigationGuardFor(WebviewEditorInput.SECURITY_VULN_DETAILS_WEBVIEW_ID).shouldNotBeNull()
    }

    it("returns a fresh guard each time") {
      val id = WebviewEditorInput.SECURITY_VULN_DETAILS_WEBVIEW_ID
      (navigationGuardFor(id) === navigationGuardFor(id)) shouldBe false
    }

    it("leaves the MCP dashboard unguarded") {
      navigationGuardFor(WebviewEditorInput.MCP_WEBVIEW_ID).shouldBeNull()
    }

    it("leaves the flow builder unguarded") {
      navigationGuardFor(WebviewEditorInput.FLOW_WEBVIEW_ID).shouldBeNull()
    }

    it("leaves the agentic tabs unguarded") {
      navigationGuardFor("agentic-tabs").shouldBeNull()
    }
  }
})
