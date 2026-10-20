package com.gitlab.eclipse.views.webview.agentplatform

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.eclipse.core.expressions.EvaluationContext
import org.eclipse.core.expressions.EvaluationResult
import org.eclipse.core.expressions.ExpressionConverter
import org.eclipse.ui.ISourceProviderListener
import org.eclipse.ui.ISources
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class AgentPlatformLayoutSourceProviderTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val key = AgentPlatformLayoutSourceProvider.LAYOUT_KEY

  class MemoryAccess(var stored: String? = null) : LayoutPreferenceAccess {
    override fun read(): String? = stored
    override fun write(value: String) {
      stored = value
    }
  }

  class Fired : ISourceProviderListener {
    val events = mutableListOf<Triple<Int, String?, Any?>>()
    override fun sourceChanged(sourcePriority: Int, sourceName: String?, sourceValue: Any?) {
      events += Triple(sourcePriority, sourceName, sourceValue)
    }

    override fun sourceChanged(sourcePriority: Int, sourceValuesByName: Map<*, *>?) = Unit
  }

  class Fixture(stored: String? = null) {
    val access = MemoryAccess(stored)
    val store = AgentPlatformLayoutStore(access)
    val queued = mutableListOf<Runnable>()
    var storeLookups = 0
    val provider = AgentPlatformLayoutSourceProvider({
      storeLookups++
      store
    }) { queued += it }
    val fired = Fired().also { provider.addSourceProviderListener(it) }

    fun drain() {
      val batch = queued.toList()
      queued.clear()
      batch.forEach { it.run() }
    }
  }

  fun evaluate(provider: AgentPlatformLayoutSourceProvider, value: String): EvaluationResult {
    val xml = """<with variable="$key"><equals value="$value"/></with>"""
    val element = DocumentBuilderFactory.newInstance().newDocumentBuilder()
      .parse(InputSource(StringReader(xml))).documentElement
    val context = EvaluationContext(null, Any())
    context.addVariable(key, provider.currentState[key])
    return ExpressionConverter.getDefault().perform(element).evaluate(context)
  }

  describe("published value") {
    it("names the one variable plugin.xml declares") {
      key shouldBe "gitlab_agent_platform_layout"
      Fixture().provider.providedSourceNames.toList() shouldContainExactly listOf(key)
    }

    it("is \"new\" when nothing is stored") {
      Fixture().provider.currentState shouldBe mapOf(key to "new")
    }

    it("is \"classic\" when classic is stored") {
      Fixture("classic").provider.currentState shouldBe mapOf(key to "classic")
    }

    it("reads the store at call time, so the first evaluation needs no change notification") {
      val f = Fixture()
      f.access.stored = "classic"

      f.provider.currentState shouldBe mapOf(key to "classic")
    }

    it("drives the toolbar's visibleWhen: exactly one of the two toggles is visible") {
      val f = Fixture()
      evaluate(f.provider, "new") shouldBe EvaluationResult.TRUE
      evaluate(f.provider, "classic") shouldBe EvaluationResult.FALSE

      f.access.stored = "classic"
      evaluate(f.provider, "new") shouldBe EvaluationResult.FALSE
      evaluate(f.provider, "classic") shouldBe EvaluationResult.TRUE
    }

    it("does not touch the store before the workbench asks") {
      Fixture().storeLookups shouldBe 0
    }
  }

  describe("change notification") {
    it("fires the new value through uiDispatch once subscribed") {
      val f = Fixture()
      f.provider.currentState

      f.store.set(AgentPlatformLayout.CLASSIC)
      f.fired.events.shouldBeEmpty()
      f.drain()

      f.fired.events shouldContainExactly listOf(Triple(ISources.WORKBENCH, key, "classic"))
    }

    it("publishes the value current at firing time, not the one captured at change time") {
      val f = Fixture()
      f.provider.currentState

      f.store.set(AgentPlatformLayout.CLASSIC)
      f.store.set(AgentPlatformLayout.NEW)
      f.drain()

      f.fired.events.map { it.third } shouldContainExactly listOf("new", "new")
    }

    it("subscribes only once however often the state is read") {
      val f = Fixture()
      repeat(3) { f.provider.currentState }

      f.store.set(AgentPlatformLayout.CLASSIC)
      f.drain()

      f.fired.events.size shouldBe 1
    }

    it("stops listening once disposed, including a fire already queued") {
      val f = Fixture()
      f.provider.currentState
      f.store.set(AgentPlatformLayout.CLASSIC)

      f.provider.dispose()
      f.drain()
      f.store.set(AgentPlatformLayout.NEW)
      f.drain()

      f.queued.shouldBeEmpty()
      f.fired.events.shouldBeEmpty()
    }

    it("does not subscribe after dispose") {
      val f = Fixture()
      f.provider.dispose()
      f.provider.currentState

      f.store.set(AgentPlatformLayout.CLASSIC)

      f.queued.shouldBeEmpty()
    }
  }
})
