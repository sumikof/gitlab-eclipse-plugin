package com.gitlab.eclipse.views.webview.agentplatform

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.storage.CapturedLog
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import org.eclipse.core.commands.ExecutionEvent

/** The two layout toggles of design AGENT-02 §6: each sets the store, and a failed write is reported. */
class AgentPlatformLayoutHandlersTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  class MemoryAccess(var stored: String? = null, val failWith: Exception? = null) : LayoutPreferenceAccess {
    override fun read(): String? = stored
    override fun write(value: String) {
      failWith?.let { throw it }
      stored = value
    }
  }

  val event = mockk<ExecutionEvent>(relaxed = true)

  describe("Use Classic Layout") {
    it("switches the store to CLASSIC and notifies nothing") {
      val store = AgentPlatformLayoutStore(MemoryAccess())
      val notices = mutableListOf<String>()

      UseClassicAgentPlatformLayoutHandler({ store }) { notices += it }.execute(event)

      store.current() shouldBe AgentPlatformLayout.CLASSIC
      notices.shouldBeEmpty()
    }

    it("is a no-op when already classic") {
      val access = MemoryAccess("classic")
      val store = AgentPlatformLayoutStore(access)
      val seen = mutableListOf<AgentPlatformLayout>()
      store.addListener { seen += it }

      UseClassicAgentPlatformLayoutHandler({ store }) { }.execute(event)

      seen.shouldBeEmpty()
    }
  }

  describe("Use New Layout") {
    it("switches the store to NEW") {
      val store = AgentPlatformLayoutStore(MemoryAccess("classic"))

      UseNewAgentPlatformLayoutHandler({ store }) { }.execute(event)

      store.current() shouldBe AgentPlatformLayout.NEW
    }
  }

  describe("failure") {
    it("logs the exception class only and shows the switch failure notice") {
      val log = CapturedLog().apply { install() }
      val failure = IllegalStateException("secret-detail")
      fun failing(stored: String?) = AgentPlatformLayoutStore(MemoryAccess(stored, failure))
      val notices = mutableListOf<String>()

      UseNewAgentPlatformLayoutHandler({ failing("classic") }) { notices += it }.execute(event)
      UseClassicAgentPlatformLayoutHandler({ failing(null) }) { notices += it }.execute(event)

      notices shouldContainExactly listOf(AgentPlatformMessages.LAYOUT_SWITCH_FAILURE, AgentPlatformMessages.LAYOUT_SWITCH_FAILURE)
      log.contains(IllegalStateException::class.java.name) shouldBe true
      log.lines.forEach { it shouldNotContain "secret-detail" }
    }
  }
})
