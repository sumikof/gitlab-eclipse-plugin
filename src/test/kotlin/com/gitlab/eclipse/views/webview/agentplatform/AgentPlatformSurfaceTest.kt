package com.gitlab.eclipse.views.webview.agentplatform

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.LanguageServerSession
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private class MemoryAccess(var stored: String? = null) : LayoutPreferenceAccess {
  override fun read(): String? = stored
  override fun write(value: String) {
    stored = value
  }
}

private class Fixture(initial: AgentPlatformLayout) {
  val store = AgentPlatformLayoutStore(MemoryAccess(initial.preferenceValue))
  val loads = mutableListOf<String>()
  var session: LanguageServerSession? = null
  val surface = AgentPlatformSurface(store, { loads += it }, { session })
}

class AgentPlatformSurfaceTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val newId = AgentPlatformLayout.NEW_WEBVIEW_ID
  val classicId = AgentPlatformLayout.CLASSIC_WEBVIEW_ID

  describe("start") {
    it("loads the new layout's id when the store says NEW") {
      val f = Fixture(AgentPlatformLayout.NEW)
      f.surface.start()
      f.loads shouldBe listOf(newId)
      f.surface.loadedWebviewId shouldBe newId
    }

    it("loads agentic-tabs when the store says CLASSIC") {
      val f = Fixture(AgentPlatformLayout.CLASSIC)
      f.surface.start()
      f.loads shouldBe listOf(classicId)
    }

    it("has loaded nothing before start") {
      Fixture(AgentPlatformLayout.NEW).surface.loadedWebviewId.shouldBeNull()
    }
  }

  describe("layout changes") {
    it("loads the classic id once on NEW -> CLASSIC") {
      val f = Fixture(AgentPlatformLayout.NEW)
      f.surface.start()
      f.store.set(AgentPlatformLayout.CLASSIC)
      f.loads shouldBe listOf(newId, classicId)
      f.surface.loadedWebviewId shouldBe classicId
    }

    it("loads nothing when notified of the id that is already loaded") {
      val access = MemoryAccess(AgentPlatformLayout.NEW.preferenceValue)
      val store = AgentPlatformLayoutStore(access)
      val loads = mutableListOf<String>()
      val surface = AgentPlatformSurface(store, { loads += it }, { null })
      surface.start()
      // The persisted value moves underneath the store, so set(NEW) is a real change that
      // notifies NEW while NEW is still on screen.
      access.stored = AgentPlatformLayout.CLASSIC.preferenceValue
      store.set(AgentPlatformLayout.NEW)
      loads shouldBe listOf(newId)
    }
  }

  describe("reload") {
    it("loads the current layout's id again") {
      val f = Fixture(AgentPlatformLayout.NEW)
      f.surface.start()
      f.surface.reload()
      f.loads shouldBe listOf(newId, newId)
    }
  }

  describe("dispose") {
    it("turns layout changes, reload and start into no-ops") {
      val f = Fixture(AgentPlatformLayout.NEW)
      f.surface.start()
      f.surface.dispose()
      f.store.set(AgentPlatformLayout.CLASSIC)
      f.surface.reload()
      f.surface.start()
      f.loads shouldBe listOf(newId)
    }

    it("can run twice") {
      val f = Fixture(AgentPlatformLayout.NEW)
      f.surface.start()
      f.surface.dispose()
      f.surface.dispose()
      f.loads shouldBe listOf(newId)
    }
  }

  describe("displayedSession") {
    it("delegates to the host") {
      val f = Fixture(AgentPlatformLayout.NEW)
      f.surface.displayedSession.shouldBeNull()
      val session = LanguageServerSession()
      f.session = session
      (f.surface.displayedSession === session) shouldBe true
    }
  }
})
