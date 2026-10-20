package com.gitlab.eclipse.views.webview.agentplatform

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.storage.CapturedLog
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

private class FakeAccess(var stored: String? = null) : LayoutPreferenceAccess {
  val writes = mutableListOf<String>()
  override fun read(): String? = stored
  override fun write(value: String) {
    writes += value
    stored = value
  }
}

class AgentPlatformLayoutStoreTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("current") {
    it("is NEW when nothing is stored") {
      AgentPlatformLayoutStore(FakeAccess()).current() shouldBe AgentPlatformLayout.NEW
    }

    it("reads the stored value") {
      AgentPlatformLayoutStore(FakeAccess("classic")).current() shouldBe AgentPlatformLayout.CLASSIC
    }
  }

  describe("set") {
    it("writes the preference value and notifies once") {
      val access = FakeAccess()
      val store = AgentPlatformLayoutStore(access)
      val seen = mutableListOf<AgentPlatformLayout>()
      store.addListener { seen += it }

      store.set(AgentPlatformLayout.CLASSIC)

      access.writes shouldBe listOf("classic")
      seen shouldBe listOf(AgentPlatformLayout.CLASSIC)
      store.current() shouldBe AgentPlatformLayout.CLASSIC
    }

    it("does nothing when the layout is unchanged") {
      val access = FakeAccess()
      val store = AgentPlatformLayoutStore(access)
      store.set(AgentPlatformLayout.CLASSIC)
      val seen = mutableListOf<AgentPlatformLayout>()
      store.addListener { seen += it }

      store.set(AgentPlatformLayout.CLASSIC)

      access.writes shouldBe listOf("classic")
      seen shouldBe emptyList()
    }

    it("does not write NEW over an unset preference") {
      val access = FakeAccess()
      AgentPlatformLayoutStore(access).set(AgentPlatformLayout.NEW)
      access.writes shouldBe emptyList()
    }
  }

  describe("listeners") {
    it("does not notify a closed listener and tolerates a double close") {
      val store = AgentPlatformLayoutStore(FakeAccess())
      val seen = mutableListOf<AgentPlatformLayout>()
      val handle = store.addListener { seen += it }
      handle.close()
      handle.close()

      store.set(AgentPlatformLayout.CLASSIC)

      seen shouldBe emptyList()
    }

    it("a throwing listener does not block the next one and is logged by class name only") {
      val captured = CapturedLog().also { it.install() }
      val store = AgentPlatformLayoutStore(FakeAccess())
      val seen = mutableListOf<AgentPlatformLayout>()
      store.addListener { throw IllegalStateException("SECRET-MESSAGE-TEXT") }
      store.addListener { seen += it }

      store.set(AgentPlatformLayout.CLASSIC)

      seen shouldBe listOf(AgentPlatformLayout.CLASSIC)
      captured.lines.any { it.contains("IllegalStateException") } shouldBe true
      captured.lines.joinToString("\n") shouldNotContain "SECRET-MESSAGE-TEXT"
    }

    // Review H10: an Error escapes set() by design, but must not leave a queued value behind to be
    // delivered, stale, by the next set().
    it("drops a queued value when a listener throws an Error, so the next set() delivers only its own") {
      val store = AgentPlatformLayoutStore(FakeAccess())
      var thrown = false
      val seen = mutableListOf<AgentPlatformLayout>()
      store.addListener {
        if (it == AgentPlatformLayout.CLASSIC && !thrown) {
          thrown = true
          store.set(AgentPlatformLayout.NEW)
          throw NoClassDefFoundError("boom")
        }
      }
      store.addListener { seen += it }

      shouldThrow<NoClassDefFoundError> { store.set(AgentPlatformLayout.CLASSIC) }
      store.set(AgentPlatformLayout.CLASSIC)

      seen shouldBe listOf(AgentPlatformLayout.CLASSIC)
    }

    it("delivers the value fixed at notify time when a listener re-enters set()") {
      val store = AgentPlatformLayoutStore(FakeAccess())
      val first = mutableListOf<AgentPlatformLayout>()
      val second = mutableListOf<AgentPlatformLayout>()
      store.addListener {
        first += it
        if (it == AgentPlatformLayout.CLASSIC) store.set(AgentPlatformLayout.NEW)
      }
      store.addListener { second += it }

      store.set(AgentPlatformLayout.CLASSIC)

      first shouldBe listOf(AgentPlatformLayout.CLASSIC, AgentPlatformLayout.NEW)
      second shouldBe listOf(AgentPlatformLayout.CLASSIC, AgentPlatformLayout.NEW)
    }
  }
})
