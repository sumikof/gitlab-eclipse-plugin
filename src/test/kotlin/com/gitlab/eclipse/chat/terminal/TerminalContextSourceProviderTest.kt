package com.gitlab.eclipse.chat.terminal

import com.gitlab.eclipse.lsp.FeatureStateChange
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.lsp.LanguageServerSession
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.eclipse.core.expressions.EvaluationContext
import org.eclipse.core.expressions.EvaluationResult
import org.eclipse.core.expressions.ExpressionConverter
import org.eclipse.ui.ISourceProviderListener
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class TerminalContextSourceProviderTest : DescribeSpec({

  val key = TerminalContextSourceProvider.ENABLED_KEY

  fun change(vararg checks: Pair<String, Boolean>) = FeatureStateChange(
    featureId = "chat_terminal_context",
    allChecks = checks.map { (id, engaged) -> FeatureStateChangeCheck(checkId = id, engaged = engaged) }
  )

  val available = change("chat-disabled-by-user" to false, "chat-include-terminal-context-unavailable" to false)
  val noTerminalContext =
    change("chat-disabled-by-user" to false, "chat-include-terminal-context-unavailable" to true)
  val noLicense = change("classic-chat-no-license" to true, "chat-include-terminal-context-unavailable" to false)

  class Connection(var session: LanguageServerSession? = LanguageServerSession()) {
    var beforeReturn: (() -> Unit)? = null
    val read: () -> LanguageServerSession? = {
      val value = session
      beforeReturn?.also { beforeReturn = null }?.invoke()
      value
    }
  }

  class Fired : ISourceProviderListener {
    val values = mutableListOf<Any?>()
    override fun sourceChanged(sourcePriority: Int, sourceName: String?, sourceValue: Any?) {
      values += sourceValue
    }

    override fun sourceChanged(sourcePriority: Int, sourceValuesByName: Map<*, *>?) = Unit
  }

  fun evaluate(provider: TerminalContextSourceProvider): EvaluationResult {
    val xml = """<with variable="$key"><equals value="true"/></with>"""
    val element = DocumentBuilderFactory.newInstance().newDocumentBuilder()
      .parse(InputSource(StringReader(xml))).documentElement
    val context = EvaluationContext(null, Any())
    context.addVariable(key, provider.currentState[key])
    return ExpressionConverter.getDefault().perform(element).evaluate(context)
  }

  describe("published value") {
    it("is false before the server has said anything") {
      TerminalContextSourceProvider(Connection().read) { it.run() }.isEnabled shouldBe false
    }

    it("is true only when no check is engaged") {
      val connection = Connection()
      val provider = TerminalContextSourceProvider(connection.read) { it.run() }

      provider.update(available, connection.session!!)
      provider.isEnabled shouldBe true

      provider.update(noTerminalContext, connection.session!!)
      provider.isEnabled shouldBe false

      provider.update(noLicense, connection.session!!)
      provider.isEnabled shouldBe false
    }

    it("ignores a change without checks") {
      val connection = Connection()
      val provider = TerminalContextSourceProvider(connection.read) { it.run() }
      provider.update(available, connection.session!!)

      provider.update(FeatureStateChange("chat_terminal_context", null), connection.session!!)

      provider.isEnabled shouldBe true
    }

    it("is a Boolean that the plugin.xml expression compares with true") {
      val connection = Connection()
      val provider = TerminalContextSourceProvider(connection.read) { it.run() }
      evaluate(provider) shouldBe EvaluationResult.FALSE

      provider.update(available, connection.session!!)

      evaluate(provider) shouldBe EvaluationResult.TRUE
    }
  }

  describe("connection changes") {
    it("stops answering true once the connection that reported it is replaced") {
      val connection = Connection()
      val provider = TerminalContextSourceProvider(connection.read) { it.run() }
      provider.update(available, connection.session!!)

      connection.session = LanguageServerSession()

      provider.isEnabled shouldBe false
    }

    it("is false while there is no connection") {
      val connection = Connection()
      val provider = TerminalContextSourceProvider(connection.read) { it.run() }
      provider.update(available, connection.session!!)

      connection.session = null

      provider.isEnabled shouldBe false
    }

    it("drops a late report from a closed connection") {
      val connection = Connection()
      val old = connection.session!!
      val provider = TerminalContextSourceProvider(connection.read) { it.run() }
      connection.session = LanguageServerSession()
      provider.update(available, connection.session!!)

      provider.update(noTerminalContext, old)

      provider.isEnabled shouldBe true
    }

    it("does not let a report that passed its check before a reconnect overwrite the new one") {
      val connection = Connection()
      val old = connection.session!!
      val fresh = LanguageServerSession()
      val provider = TerminalContextSourceProvider(connection.read) { it.run() }
      connection.beforeReturn = {
        connection.session = fresh
        provider.update(available, fresh)
      }

      provider.update(noTerminalContext, old)

      provider.isEnabled shouldBe true
    }

    it("reset() clears the value and republishes false") {
      val connection = Connection()
      val provider = TerminalContextSourceProvider(connection.read) { it.run() }
      val fired = Fired()
      provider.addSourceProviderListener(fired)
      provider.update(available, connection.session!!)

      provider.reset()

      provider.isEnabled shouldBe false
      fired.values shouldContainExactly listOf(true, false)
    }
  }

  describe("firing") {
    it("fires through the UI dispatch seam with the value computed at firing time") {
      val connection = Connection()
      val held = mutableListOf<Runnable>()
      val provider = TerminalContextSourceProvider(connection.read) { held += it }
      val fired = Fired()
      provider.addSourceProviderListener(fired)

      provider.update(available, connection.session!!)
      fired.values shouldBe emptyList()
      connection.session = LanguageServerSession()
      held.forEach { it.run() }

      fired.values shouldContainExactly listOf(false)
    }
  }
})
