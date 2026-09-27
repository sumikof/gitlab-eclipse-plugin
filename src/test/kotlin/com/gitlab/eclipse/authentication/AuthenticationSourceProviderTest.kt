package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.lsp.FeatureStateChange
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.lsp.LanguageServerSession
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.core.expressions.EvaluationContext
import org.eclipse.core.expressions.EvaluationResult
import org.eclipse.core.expressions.ExpressionConverter
import org.eclipse.ui.ISourceProviderListener
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Spec for `AuthenticationSourceProvider` (design §8.1 / §12.1 / §23).
 *
 * The provider is exercised through its two seams only: the current-connection read and the UI
 * dispatch. Nothing here touches `Display`, so the spec runs headless.
 */
class AuthenticationSourceProviderTest : DescribeSpec({

  val key = AuthenticationSourceProvider.SIGN_IN_REQUIRED_KEY

  fun change(vararg checks: Pair<String, Boolean>) = FeatureStateChange(
    featureId = "authentication",
    allChecks = checks.map { (id, engaged) -> FeatureStateChangeCheck(checkId = id, engaged = engaged) }
  )

  val signInRequired = change("authentication-required" to true)
  val invalidTokenOnly = change("authentication-required" to false, "invalid-token" to true)
  val authenticated = change("authentication-required" to false, "invalid-token" to false, "other" to true)
  val noChecks = FeatureStateChange(featureId = "authentication", allChecks = null)

  /** A fake of `GitLabLanguageServerWrapper.currentSnapshot?.session` whose reads can be intercepted. */
  class FakeConnection(@Volatile var session: LanguageServerSession? = LanguageServerSession()) {
    /** Runs once, on the next read, after the value has been read and before it is returned. */
    @Volatile
    var beforeReturn: (() -> Unit)? = null
    private val fired = AtomicBoolean(false)

    val read: () -> LanguageServerSession? = {
      val stale = session
      val hook = beforeReturn
      if (hook != null && fired.compareAndSet(false, true)) hook()
      stale
    }
  }

  /** Records what reached the UI dispatch seam and what the provider fired. */
  class UiRecorder {
    val dispatched = mutableListOf<Runnable>()
    val fired = mutableListOf<Any?>()
    val runImmediately: (Runnable) -> Unit = { it.run() }
    val hold: (Runnable) -> Unit = { dispatched += it }
    val listener = object : ISourceProviderListener {
      override fun sourceChanged(sourcePriority: Int, sourceName: String?, sourceValue: Any?) {
        fired += (sourceName to sourceValue)
      }

      override fun sourceChanged(sourcePriority: Int, sourceValuesByName: Map<*, *>?) = Unit
    }
  }

  fun expression(): org.eclipse.core.expressions.Expression {
    val xml = """<with variable="$key"><equals value="true"/></with>"""
    val element: Element = DocumentBuilderFactory.newInstance()
      .newDocumentBuilder()
      .parse(InputSource(StringReader(xml)))
      .documentElement
    return ExpressionConverter.getDefault().perform(element)
  }

  fun evaluate(provider: AuthenticationSourceProvider): EvaluationResult {
    val context = EvaluationContext(null, Any())
    context.addVariable(key, provider.currentState[key])
    return expression().evaluate(context)
  }

  describe("published value") {
    it("starts unknown and publishes false") {
      val connection = FakeConnection()
      val provider = AuthenticationSourceProvider(connection.read, UiRecorder().runImmediately)

      provider.authState shouldBe AuthState.UNKNOWN
      provider.stored shouldBe null
      provider.currentState[key] shouldBe false
    }

    it("authentication-required engaged -> sign in required, publishes true") {
      val connection = FakeConnection()
      val provider = AuthenticationSourceProvider(connection.read, UiRecorder().runImmediately)

      provider.update(signInRequired, connection.session!!, 1)

      provider.authState shouldBe AuthState.SIGN_IN_REQUIRED
      provider.currentState[key] shouldBe true
    }

    it("invalid-token engaged alone -> sign in required, publishes true") {
      val connection = FakeConnection()
      val provider = AuthenticationSourceProvider(connection.read, UiRecorder().runImmediately)

      provider.update(invalidTokenOnly, connection.session!!, 1)

      provider.authState shouldBe AuthState.SIGN_IN_REQUIRED
      provider.currentState[key] shouldBe true
    }

    it("no relevant check engaged -> authenticated, publishes false (also after sign in required)") {
      val connection = FakeConnection()
      val provider = AuthenticationSourceProvider(connection.read, UiRecorder().runImmediately)

      provider.update(signInRequired, connection.session!!, 1)
      provider.currentState[key] shouldBe true

      provider.update(authenticated, connection.session!!, 2)

      provider.authState shouldBe AuthState.AUTHENTICATED
      provider.currentState[key] shouldBe false
    }

    it("allChecks == null leaves the internal state untouched") {
      val connection = FakeConnection()
      val provider = AuthenticationSourceProvider(connection.read, UiRecorder().runImmediately)

      provider.update(noChecks, connection.session!!, 1)
      provider.authState shouldBe AuthState.UNKNOWN
      provider.stored shouldBe null

      provider.update(signInRequired, connection.session!!, 2)
      provider.update(noChecks, connection.session!!, 3)
      provider.authState shouldBe AuthState.SIGN_IN_REQUIRED
      provider.stored?.generation shouldBe 2
      provider.currentState[key] shouldBe true
    }

    it("a value stored for a session other than the current one is not published") {
      val connection = FakeConnection()
      val provider = AuthenticationSourceProvider(connection.read, UiRecorder().runImmediately)
      val old = connection.session!!

      provider.update(signInRequired, old, 1)
      provider.currentState[key] shouldBe true

      connection.session = LanguageServerSession()

      provider.currentState[key] shouldBe false
      provider.authState shouldBe AuthState.SIGN_IN_REQUIRED
    }

    it("reset(session, generation) writes an UNKNOWN tombstone and publishes false") {
      val connection = FakeConnection()
      val provider = AuthenticationSourceProvider(connection.read, UiRecorder().runImmediately)

      provider.update(signInRequired, connection.session!!, 1)
      provider.reset(connection.session, 2)

      provider.authState shouldBe AuthState.UNKNOWN
      provider.stored shouldBe SessionAuthState(connection.session!!, 2, AuthState.UNKNOWN)
      provider.currentState[key] shouldBe false
    }

    it("reset(null, generation) clears the stored value") {
      val connection = FakeConnection()
      val provider = AuthenticationSourceProvider(connection.read, UiRecorder().runImmediately)

      provider.update(signInRequired, connection.session!!, 1)
      connection.session = null
      provider.reset(null, 2)

      provider.stored shouldBe null
      provider.currentState[key] shouldBe false
    }

    it("getProvidedSourceNames names only the sign in variable") {
      val provider = AuthenticationSourceProvider(FakeConnection().read, UiRecorder().runImmediately)

      provider.providedSourceNames.toList() shouldContainExactly listOf(key)
    }
  }

  describe("published type") {
    it("getCurrentState() holds a Boolean, never a String") {
      val connection = FakeConnection()
      val provider = AuthenticationSourceProvider(connection.read, UiRecorder().runImmediately)

      provider.currentState[key].shouldBeInstanceOf<Boolean>()
      provider.update(signInRequired, connection.session!!, 1)
      provider.currentState[key].shouldBeInstanceOf<Boolean>()
      provider.currentState[key] shouldBe true
    }

    it("fires a Boolean through the UI dispatch seam") {
      val connection = FakeConnection()
      val ui = UiRecorder()
      val provider = AuthenticationSourceProvider(connection.read, ui.hold)
      provider.addSourceProviderListener(ui.listener)

      provider.update(signInRequired, connection.session!!, 1)

      ui.fired shouldBe emptyList()
      ui.dispatched.size shouldBe 1
      ui.dispatched.single().run()
      ui.fired shouldContainExactly listOf(key to true)
      (ui.fired.single() as Pair<*, *>).second.shouldBeInstanceOf<Boolean>()
    }

    it("the plugin.xml expression evaluates TRUE only when sign in is required") {
      val connection = FakeConnection()
      val provider = AuthenticationSourceProvider(connection.read, UiRecorder().runImmediately)

      evaluate(provider) shouldBe EvaluationResult.FALSE

      provider.update(signInRequired, connection.session!!, 1)
      evaluate(provider) shouldBe EvaluationResult.TRUE

      provider.update(authenticated, connection.session!!, 2)
      evaluate(provider) shouldBe EvaluationResult.FALSE

      provider.update(signInRequired, connection.session!!, 3)
      evaluate(provider) shouldBe EvaluationResult.TRUE
      connection.session = LanguageServerSession()
      evaluate(provider) shouldBe EvaluationResult.FALSE

      provider.reset(connection.session, 4)
      evaluate(provider) shouldBe EvaluationResult.FALSE
    }
  }

  describe("generation contract") {
    it("stores the generation it was given verbatim, for update and for the tombstone") {
      val connection = FakeConnection()
      val provider = AuthenticationSourceProvider(connection.read, UiRecorder().runImmediately)

      provider.update(signInRequired, connection.session!!, 7)
      provider.stored shouldBe SessionAuthState(connection.session!!, 7, AuthState.SIGN_IN_REQUIRED)

      provider.reset(connection.session, 9)
      provider.stored shouldBe SessionAuthState(connection.session!!, 9, AuthState.UNKNOWN)
    }

    it("rejects an older generation for the same session") {
      val connection = FakeConnection()
      val provider = AuthenticationSourceProvider(connection.read, UiRecorder().runImmediately)

      provider.update(authenticated, connection.session!!, 5)
      provider.update(signInRequired, connection.session!!, 4)

      provider.stored shouldBe SessionAuthState(connection.session!!, 5, AuthState.AUTHENTICATED)
      provider.currentState[key] shouldBe false
    }

    it("rejects an update older than the tombstone") {
      val connection = FakeConnection()
      val provider = AuthenticationSourceProvider(connection.read, UiRecorder().runImmediately)

      provider.reset(connection.session, 6)
      provider.update(signInRequired, connection.session!!, 5)

      provider.stored shouldBe SessionAuthState(connection.session!!, 6, AuthState.UNKNOWN)
      provider.currentState[key] shouldBe false
    }

    it("accepts the same generation for a new session") {
      val connection = FakeConnection()
      val provider = AuthenticationSourceProvider(connection.read, UiRecorder().runImmediately)

      provider.update(signInRequired, connection.session!!, 3)
      val next = LanguageServerSession()
      connection.session = next
      provider.update(signInRequired, next, 3)

      provider.stored shouldBe SessionAuthState(next, 3, AuthState.SIGN_IN_REQUIRED)
      provider.currentState[key] shouldBe true
    }
  }

  describe("deterministic races") {
    it("old session update passes the check, then reset + connection switch, then writes -> false") {
      val connection = FakeConnection()
      val provider = AuthenticationSourceProvider(connection.read, UiRecorder().runImmediately)
      val old = connection.session!!
      val next = LanguageServerSession()

      connection.beforeReturn = {
        connection.session = next
        provider.reset(next, 2)
      }
      provider.update(signInRequired, old, 1)

      provider.currentState[key] shouldBe false
      provider.stored shouldBe SessionAuthState(next, 2, AuthState.UNKNOWN)
    }

    it("new session value written while the old session update is stalled -> new value kept") {
      val connection = FakeConnection()
      val provider = AuthenticationSourceProvider(connection.read, UiRecorder().runImmediately)
      val old = connection.session!!
      val next = LanguageServerSession()

      connection.beforeReturn = {
        connection.session = next
        provider.update(signInRequired, next, 2)
      }
      provider.update(authenticated, old, 1)

      provider.stored shouldBe SessionAuthState(next, 2, AuthState.SIGN_IN_REQUIRED)
      provider.currentState[key] shouldBe true
    }

    it("same session: reset lands between the stale update's check and its CAS -> update rejected") {
      val connection = FakeConnection()
      val provider = AuthenticationSourceProvider(connection.read, UiRecorder().runImmediately)
      val session = connection.session!!

      connection.beforeReturn = { provider.reset(session, 6) }
      provider.update(signInRequired, session, 5)

      provider.stored shouldBe SessionAuthState(session, 6, AuthState.UNKNOWN)
      provider.currentState[key] shouldBe false
    }
  }
})
