package com.gitlab.eclipse.views.webview

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private const val WEBVIEW_URL = "http://127.0.0.1:39111/webview/security-vuln-details?_csrf=abc"
private const val OTHER_WEBVIEW_URL = "http://127.0.0.1:40222/webview/security-vuln-details?_csrf=def"

private fun openGuard() = TopLevelNavigationGuard().apply { expectLoad(WEBVIEW_URL) }

/** A guard whose expected load was admitted and then completed. */
private fun completedGuard() = openGuard().apply {
  allows(WEBVIEW_URL)
  loadCompleted()
}

class TopLevelNavigationGuardTest : DescribeSpec({
  describe("TopLevelNavigationGuard before any expected load") {
    it("blocks a top-level navigation") {
      TopLevelNavigationGuard().allows(WEBVIEW_URL) shouldBe false
    }
  }

  describe("TopLevelNavigationGuard while the initial load is expected") {
    it("allows the exact expected url") {
      openGuard().allows(WEBVIEW_URL) shouldBe true
    }

    it("allows the server's redirect to the trailing-slash variant") {
      openGuard().allows(
        "http://127.0.0.1:39111/webview/security-vuln-details/?_csrf=abc",
      ) shouldBe true
    }

    it("ignores only the fragment when matching") {
      openGuard().allows("http://127.0.0.1:39111/webview/security-vuln-details?_csrf=abc#top") shouldBe true
    }

    // PR #90 Codex round 1: the page is interactive before `completed`, so a formatted relative link
    // such as `[**x**](?error=1)` reaches the guard as the same path with a different query.
    it("blocks the same path without the expected query") {
      openGuard().allows("http://127.0.0.1:39111/webview/security-vuln-details") shouldBe false
      openGuard().allows("http://127.0.0.1:39111/webview/security-vuln-details#top") shouldBe false
    }

    it("blocks the same path with a replaced, extended or reordered query") {
      val guard = openGuard()
      guard.allows("http://127.0.0.1:39111/webview/security-vuln-details?error=1") shouldBe false
      guard.allows("http://127.0.0.1:39111/webview/security-vuln-details?_csrf=def") shouldBe false
      guard.allows("http://127.0.0.1:39111/webview/security-vuln-details?_csrf=abc&error=1") shouldBe false
      guard.allows("http://127.0.0.1:39111/webview/security-vuln-details?_csrf=abc&") shouldBe false
      guard.allows("http://127.0.0.1:39111/webview/security-vuln-details/?error=1") shouldBe false
    }

    it("still admits the expected load after refusing a same-path link with another query") {
      val guard = openGuard()
      guard.allows("http://127.0.0.1:39111/webview/security-vuln-details?error=1") shouldBe false
      guard.allows(WEBVIEW_URL) shouldBe true
    }

    it("blocks the same path on another port") {
      openGuard().allows(
        "http://127.0.0.1:39112/webview/security-vuln-details?_csrf=abc",
      ) shouldBe false
    }

    it("blocks the same path on another host") {
      openGuard().allows(
        "http://example.com:39111/webview/security-vuln-details?_csrf=abc",
      ) shouldBe false
    }

    it("blocks the same path under another scheme") {
      openGuard().allows(
        "https://127.0.0.1:39111/webview/security-vuln-details?_csrf=abc",
      ) shouldBe false
    }

    it("blocks another path on the same origin") {
      openGuard().allows("http://127.0.0.1:39111/webview/root/mcp?_csrf=abc") shouldBe false
    }

    it("blocks a path that only starts with the expected one") {
      openGuard().allows(
        "http://127.0.0.1:39111/webview/security-vuln-details/evil?_csrf=abc",
      ) shouldBe false
    }

    it("blocks an external https url") {
      openGuard().allows("https://gitlab.com/some/project") shouldBe false
    }

    it("blocks about:blank") {
      openGuard().allows("about:blank") shouldBe false
    }

    it("blocks a javascript: url") {
      openGuard().allows("javascript:alert(1)") shouldBe false
    }

    it("blocks a null location") {
      openGuard().allows(null) shouldBe false
    }

    it("blocks an unparseable location") {
      openGuard().allows("http://127.0.0.1:39111/web view/%zz") shouldBe false
    }
  }

  describe("TopLevelNavigationGuard after the load completed") {
    it("blocks the same url") {
      completedGuard().allows(WEBVIEW_URL) shouldBe false
    }

    it("blocks a fragment-only change") {
      completedGuard().allows("$WEBVIEW_URL#section") shouldBe false
    }

    it("blocks an http url") {
      completedGuard().allows("http://example.com/") shouldBe false
    }

    it("blocks an https url") {
      completedGuard().allows("https://gitlab.com/some/project") shouldBe false
    }
  }

  describe("TopLevelNavigationGuard on a second expected load") {
    it("re-opens the window for the new url after a completed load") {
      val guard = completedGuard().apply { expectLoad(OTHER_WEBVIEW_URL) }

      guard.allows(OTHER_WEBVIEW_URL) shouldBe true
    }

    it("no longer treats the old url as special") {
      val guard = openGuard().apply { expectLoad(OTHER_WEBVIEW_URL) }

      guard.allows(WEBVIEW_URL) shouldBe false
    }
  }

  // WebKitGTK never sets `LocationEvent.top` on `changing`, so nothing may depend on a frame flag.
  describe("TopLevelNavigationGuard without a frame flag") {
    it("refuses an off-window location, whatever frame it came from") {
      openGuard().allows("https://example.com/frame") shouldBe false
    }

    it("refuses every location before any expected load") {
      TopLevelNavigationGuard().allows("https://example.com/frame") shouldBe false
    }
  }

  describe("TopLevelNavigationGuard admission") {
    it("keeps the window open when a load completes before the expected one was admitted") {
      val guard = openGuard().apply { loadCompleted() }

      guard.allows(WEBVIEW_URL) shouldBe true
    }

    it("closes the window when the admitted load completes") {
      val guard = openGuard().apply {
        allows(WEBVIEW_URL) shouldBe true
        loadCompleted()
      }

      guard.allows(WEBVIEW_URL) shouldBe false
    }

    it("does not count a refused location as admission") {
      val guard = openGuard().apply {
        allows("https://example.com/") shouldBe false
        loadCompleted()
      }

      guard.allows(WEBVIEW_URL) shouldBe true
    }

    it("resets admission on a new expected load") {
      val guard = openGuard().apply {
        allows(WEBVIEW_URL)
        expectLoad(OTHER_WEBVIEW_URL)
        loadCompleted()
      }

      guard.allows(OTHER_WEBVIEW_URL) shouldBe true
    }
  }

  describe("TopLevelNavigationGuard with an unparseable expected url") {
    val unparseable = { TopLevelNavigationGuard().apply { expectLoad("http://127.0.0.1:39111/web view/%zz") } }

    it("refuses the following load") {
      unparseable().allows("http://127.0.0.1:39111/web view/%zz") shouldBe false
    }

    it("refuses any other location") {
      unparseable().allows(WEBVIEW_URL) shouldBe false
    }

    it("reports that its expected url did not parse") {
      unparseable().expectedLoadUnparseable shouldBe true
    }

    it("does not report that for a parseable expected url") {
      openGuard().expectedLoadUnparseable shouldBe false
    }

    it("does not report that once no load is expected") {
      TopLevelNavigationGuard().expectedLoadUnparseable shouldBe false
      completedGuard().expectedLoadUnparseable shouldBe false
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
