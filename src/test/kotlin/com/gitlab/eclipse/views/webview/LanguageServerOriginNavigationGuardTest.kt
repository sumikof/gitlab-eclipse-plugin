package com.gitlab.eclipse.views.webview

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

private const val BASE = "http://127.0.0.1:4000/webview/root/duoAgentPlatform?_csrf=t1"
private const val ONE_SECOND_NANOS = 1_000_000_000L

private class FakeClock(var now: Long = 0L) : () -> Long {
  override fun invoke(): Long = now
}

private fun guard(clock: FakeClock = FakeClock()) =
  LanguageServerOriginNavigationGuard(clock).apply { expectLoad(BASE) }

class LanguageServerOriginNavigationGuardTest : DescribeSpec({
  describe("ALLOW") {
    it("allows about:blank") {
      guard().decide("about:blank") shouldBe NavigationDecision.Allow
    }

    it("allows the expected url") {
      guard().decide(BASE) shouldBe NavigationDecision.Allow
    }

    it("allows the expected url with a trailing slash and a fragment") {
      guard().decide("http://127.0.0.1:4000/webview/root/duoAgentPlatform/?_csrf=t1#top") shouldBe NavigationDecision.Allow
    }

    it("allows a same-origin /webview/ page that carries a _csrf parameter") {
      guard().decide("http://127.0.0.1:4000/webview/root/duoAgentPlatform/chat?_csrf=t2") shouldBe NavigationDecision.Allow
    }
  }

  describe("RELOAD") {
    val stripped = "http://127.0.0.1:4000/webview/root/duoAgentPlatform/chat"

    it("asks for a reload once, then refuses within the same load") {
      val g = guard()
      g.decide(stripped) shouldBe NavigationDecision.Reload
      g.decide(stripped) shouldBe NavigationDecision.Refuse
    }

    it("marks only the repeated reload as a warning") {
      val g = guard()
      g.decide(stripped)
      g.decide("http://127.0.0.1:4000/group/proj/-/issues/1")
      g.refusalNote().isWarning shouldBe false
      g.decide(stripped)
      g.refusalNote().isWarning shouldBe true
    }

    it("a new expected load resets the once-limit") {
      val g = guard()
      g.decide(stripped) shouldBe NavigationDecision.Reload
      g.expectLoad(BASE)
      g.decide(stripped) shouldBe NavigationDecision.Reload
    }
  }

  describe("REFUSE") {
    it("refuses a same-origin path outside /webview/") {
      guard().decide("http://127.0.0.1:4000/group/proj/-/issues/1") shouldBe NavigationDecision.Refuse
    }

    it("refuses a loopback url on another port instead of opening it externally") {
      guard().decide("http://127.0.0.1:5000/webview/root/duoAgentPlatform?_csrf=old") shouldBe NavigationDecision.Refuse
    }

    it("refuses localhost") {
      guard().decide("http://localhost:9/x") shouldBe NavigationDecision.Refuse
    }

    it("refuses the IPv6 loopback") {
      guard().decide("http://[::1]:9/x") shouldBe NavigationDecision.Refuse
    }

    it("refuses file:") {
      guard().decide("file:///etc/passwd") shouldBe NavigationDecision.Refuse
    }

    it("refuses javascript:") {
      guard().decide("javascript:alert(1)") shouldBe NavigationDecision.Refuse
    }

    it("refuses null") {
      guard().decide(null) shouldBe NavigationDecision.Refuse
    }

    it("refuses an unparsable location") {
      guard().decide("http://exa mple.com/") shouldBe NavigationDecision.Refuse
    }
  }

  describe("EXTERNAL") {
    it("hands another http(s) url to the external browser") {
      guard().decide("https://gitlab.com/a") shouldBe NavigationDecision.External("https://gitlab.com/a")
    }

    it("allows at most one external open per second") {
      val clock = FakeClock(10 * ONE_SECOND_NANOS)
      val g = guard(clock)
      g.decide("https://gitlab.com/a") shouldBe NavigationDecision.External("https://gitlab.com/a")
      clock.now += ONE_SECOND_NANOS - 1
      g.decide("https://gitlab.com/b") shouldBe NavigationDecision.Refuse
      clock.now += 1
      g.decide("https://gitlab.com/c") shouldBe NavigationDecision.External("https://gitlab.com/c")
    }
  }

  describe("policy flags") {
    it("keeps the engine's context menu and refuses new windows") {
      guard().suppressContextMenu shouldBe false
      guard().refuseNewWindows shouldBe true
    }
  }

  describe("toString") {
    it("names no location") {
      val g = guard()
      g.decide("https://gitlab.com/a")
      listOf(
        NavigationDecision.External("https://gitlab.com/a?_csrf=x").toString(),
        NavigationDecision.Allow.toString(),
        NavigationDecision.Reload.toString(),
        NavigationDecision.Refuse.toString(),
        g.toString(),
        g.refusalNote().message,
      ).forEach {
        it shouldNotContain "gitlab.com"
        it shouldNotContain "_csrf"
        it shouldNotContain "127.0.0.1"
      }
    }
  }
})
