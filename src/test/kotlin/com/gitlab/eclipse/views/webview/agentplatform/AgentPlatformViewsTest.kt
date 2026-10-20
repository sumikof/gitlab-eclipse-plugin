package com.gitlab.eclipse.views.webview.agentplatform

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.preferences.storage.CapturedLog
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.eclipse.swt.SWTError

/** A view whose reload lands at once on [sessionAfterReload], like a resolution that already finished. */
private class FakeSurface(
  override var displayedSession: LanguageServerSession? = null,
  private val sessionAfterReload: LanguageServerSession? = null,
  private val failure: Throwable? = null,
) : ReloadableWebviewSurface {
  var reloads = 0

  override fun reload() {
    reloads += 1
    failure?.let { throw it }
    displayedSession = sessionAfterReload
  }
}

class AgentPlatformViewsTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  beforeEach {
    // A process-wide object: start every test from an empty, active registry.
    AgentPlatformViews.onDeactivate()
    AgentPlatformViews.onActivate()
  }

  describe("reloadStale") {
    it("reloads surfaces showing no session or another session, not those already on it") {
      val current = LanguageServerSession()
      val none = FakeSurface(null)
      val other = FakeSurface(LanguageServerSession())
      val same = FakeSurface(current)
      listOf(none, other, same).forEach(AgentPlatformViews::register)

      AgentPlatformViews.reloadStale(current)

      listOf(none.reloads, other.reloads, same.reloads) shouldBe listOf(1, 1, 0)
    }

    it("is idempotent for the same session") {
      val current = LanguageServerSession()
      val surface = FakeSurface(null, sessionAfterReload = current)
      AgentPlatformViews.register(surface)

      AgentPlatformViews.reloadStale(current)
      AgentPlatformViews.reloadStale(current)

      surface.reloads shouldBe 1
    }

    it("skips an unregistered surface") {
      val surface = FakeSurface(null)
      AgentPlatformViews.register(surface)
      AgentPlatformViews.unregister(surface)

      AgentPlatformViews.reloadStale(LanguageServerSession())

      surface.reloads shouldBe 0
    }

    it("registers the same surface once") {
      val surface = FakeSurface(null)
      AgentPlatformViews.register(surface)
      AgentPlatformViews.register(surface)

      AgentPlatformViews.reloadStale(LanguageServerSession())

      surface.reloads shouldBe 1
    }

    it("reloads nothing when there is no current session") {
      val surface = FakeSurface(LanguageServerSession())
      AgentPlatformViews.register(surface)

      AgentPlatformViews.reloadStale(null)

      surface.reloads shouldBe 0
    }

    it("keeps going past a surface whose reload throws, logging the class only") {
      val log = CapturedLog().apply { install() }
      val failing = FakeSurface(null, failure = IllegalStateException("http://127.0.0.1:1/?_csrf=secret"))
      val healthy = FakeSurface(null)
      AgentPlatformViews.register(failing)
      AgentPlatformViews.register(healthy)

      AgentPlatformViews.reloadStale(LanguageServerSession())

      healthy.reloads shouldBe 1
      log.contains("IllegalStateException") shouldBe true
      log.contains("_csrf") shouldBe false
    }

    // Review H2: SWTError extends Error; reloadStale runs in the finally of an asyncExec runnable,
    // so an escaping one would reach the event loop with its message and skip the other views.
    it("keeps going past a surface whose reload throws an SWTError, logging the class only") {
      val log = CapturedLog().apply { install() }
      val failing = FakeSurface(null, failure = SWTError("http://127.0.0.1:1/?_csrf=secret"))
      val healthy = FakeSurface(null)
      AgentPlatformViews.register(failing)
      AgentPlatformViews.register(healthy)

      AgentPlatformViews.reloadStale(LanguageServerSession())

      healthy.reloads shouldBe 1
      log.contains("SWTError") shouldBe true
      log.contains("_csrf") shouldBe false
    }
  }

  describe("bundle lifecycle") {
    it("after onDeactivate nothing reloads and register is ignored") {
      val before = FakeSurface(null)
      AgentPlatformViews.register(before)
      AgentPlatformViews.onDeactivate()
      val during = FakeSurface(null)
      AgentPlatformViews.register(during)

      AgentPlatformViews.reloadStale(LanguageServerSession())

      listOf(before.reloads, during.reloads) shouldBe listOf(0, 0)
    }

    it("onActivate accepts registrations again") {
      AgentPlatformViews.onDeactivate()
      AgentPlatformViews.onActivate()
      val surface = FakeSurface(null)
      AgentPlatformViews.register(surface)

      AgentPlatformViews.reloadStale(LanguageServerSession())

      surface.reloads shouldBe 1
    }
  }
})
