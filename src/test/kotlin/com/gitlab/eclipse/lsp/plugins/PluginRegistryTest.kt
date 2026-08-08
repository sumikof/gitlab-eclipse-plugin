package com.gitlab.eclipse.lsp.plugins

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.plugins.annotations.PluginNotification
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageType
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle

/**
 * The plugin bus also carries classic Duo Chat, so the four handler shapes that existed before the
 * originating connection was propagated are pinned here as keep-behaviour: they are green on both
 * sides of the change by design, and they exist to prove the change did not move them.
 *
 * [TestPluginController] and [TestPayload] are reused from `PluginMessageServiceTest` rather than
 * restated, so there is one declaration of what "the four shapes" are.
 */
class PluginRegistryTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  // Built per test, not at spec init: PluginRegistry creates its logger eagerly, so it must be
  // constructed after LoggingKotestExtension has mocked Platform.getLog.
  fun serviceFor(vararg controllers: PluginController) =
    PluginMessageService(PluginRegistry(controllers.toList()))

  describe("the four handler shapes that predate session propagation") {
    it("keep-behaviour: a notification taking no argument resolves and returns its value") {
      val route = PluginMessageRoute("test", PluginMessageType.NOTIFICATION, "notification")

      serviceFor(TestPluginController()).dispatch(route, null, session = null).get() shouldBe 456
    }

    it("keep-behaviour: a notification taking a payload receives the parsed payload") {
      val route = PluginMessageRoute("test", PluginMessageType.NOTIFICATION, "notif-payload")
      val payload = JsonObject().apply { add("message", JsonPrimitive("Hello World!")) }

      serviceFor(TestPluginController()).dispatch(route, payload, session = null).get() shouldBe "Hello World!"
    }

    it("keep-behaviour: a request taking no argument resolves and returns its value") {
      val route = PluginMessageRoute("test", PluginMessageType.REQUEST, "request")

      serviceFor(TestPluginController()).dispatch(route, null, session = null).get() shouldBe 123
    }

    it("keep-behaviour: a request taking a payload receives the parsed payload") {
      val route = PluginMessageRoute("test", PluginMessageType.REQUEST, "request-payload")
      val payload = JsonObject().apply { add("message", JsonPrimitive("Hello World!")) }

      serviceFor(TestPluginController()).dispatch(route, payload, session = null).get() shouldBe "Hello World!"
    }
  }

  describe("handlers that do not declare a session") {
    it("keep-behaviour: a no-argument handler still resolves when the message carries a session") {
      val route = PluginMessageRoute("test", PluginMessageType.NOTIFICATION, "notification")

      serviceFor(TestPluginController()).dispatch(route, null, LanguageServerSession()).get() shouldBe 456
    }

    it("keep-behaviour: a payload handler still resolves when the message carries a session") {
      val route = PluginMessageRoute("test", PluginMessageType.REQUEST, "request-payload")
      val payload = JsonObject().apply { add("message", JsonPrimitive("Hello World!")) }

      serviceFor(TestPluginController()).dispatch(route, payload, LanguageServerSession()).get() shouldBe "Hello World!"
    }
  }

  describe("handlers that declare the originating session") {
    it("gives a session-only handler the very session dispatch was called with") {
      val controller = SessionAwareTestController()
      val session = LanguageServerSession()
      val route = PluginMessageRoute("session-aware", PluginMessageType.NOTIFICATION, "session-only")

      serviceFor(controller).dispatch(route, null, session).get()

      controller.receivedSession shouldBeSameInstanceAs session
    }

    it("gives a payload-and-session handler the very session dispatch was called with") {
      val controller = SessionAwareTestController()
      val session = LanguageServerSession()
      val route = PluginMessageRoute("session-aware", PluginMessageType.NOTIFICATION, "payload-and-session")
      val payload = JsonObject().apply { add("message", JsonPrimitive("Hello World!")) }

      serviceFor(controller).dispatch(route, payload, session).get()

      controller.receivedSession shouldBeSameInstanceAs session
    }

    it("gives a payload-and-session handler the parsed payload") {
      val controller = SessionAwareTestController()
      val route = PluginMessageRoute("session-aware", PluginMessageType.NOTIFICATION, "payload-and-session")
      val payload = JsonObject().apply { add("message", JsonPrimitive("Hello World!")) }

      serviceFor(controller).dispatch(route, payload, LanguageServerSession()).get()

      controller.receivedPayload shouldBe TestPayload("Hello World!")
    }

    // PluginMessageService catches only on its payload arm, so a session-aware handler that takes
    // no payload used to complete its future exceptionally — and a notification's future is never
    // looked at.
    // Both tests assert through the log rather than through the absence of a throw, because a
    // version that catches and does nothing is just as silent as one that does not catch at all.
    // `.get()` still runs first, so a version that logs and rethrows fails here too.
    it("logs what a session-only handler throws instead of losing it") {
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      val route = PluginMessageRoute("session-aware", PluginMessageType.NOTIFICATION, "session-only-throws")

      serviceFor(SessionAwareTestController()).dispatch(route, null, LanguageServerSession()).get()

      verify(exactly = 1) { log.error(any(), any<Throwable>()) }
    }

    // Keep-behaviour, and the reason the payload-and-session arm adds no containment of its own:
    // this arm reaches PluginMessageService's payload branch, which has always caught and logged.
    // Green before and after the fix by design — its job is to fail if that catch ever goes away.
    it("keep-behaviour: logs what a payload-and-session handler throws instead of losing it") {
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      val route = PluginMessageRoute("session-aware", PluginMessageType.NOTIFICATION, "payload-and-session-throws")
      val payload = JsonObject().apply { add("message", JsonPrimitive("Hello World!")) }

      serviceFor(SessionAwareTestController()).dispatch(route, payload, LanguageServerSession()).get()

      verify(exactly = 1) { log.error(any(), any<Throwable>()) }
    }

    // The invocation count alone stopped being discriminating once the arm below it was contained:
    // with the guard deleted, the handler is invoked with null, Kotlin's non-null parameter check
    // throws before the body runs, and the containment logs it — so the count is still 0 and the
    // future still completes. The error-log assertion is what tells "dropped" apart from
    // "attempted, blew up, and was tidied away"; neither half detects that alone.
    it("does not invoke a handler that asks for a session when the message names none") {
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      val controller = SessionAwareTestController()
      val route = PluginMessageRoute("session-aware", PluginMessageType.NOTIFICATION, "session-only")

      serviceFor(controller).dispatch(route, null, session = null).get()

      controller.invocations shouldBe 0
      verify(exactly = 0) { log.error(any(), any<Throwable>()) }
    }
  }

  describe("the argument constraint") {
    it("rejects a two-argument handler whose last argument is not a session") {
      shouldThrow<IllegalStateException> { PluginRegistry(listOf(TwoArgumentTestController())) }
    }
  }
})

/** Two arguments whose last one is not a session — the shape the registry has always rejected. */
class TwoArgumentTestController : PluginController("two-argument") {
  @PluginNotification("two-payloads")
  fun twoPayloads(first: TestPayload, second: TestPayload) = "${first.message}${second.message}"
}

/** Records what the bus handed to the handler shapes that name the originating connection. */
class SessionAwareTestController : PluginController("session-aware") {
  var invocations = 0
  var receivedSession: LanguageServerSession? = null
  var receivedPayload: TestPayload? = null

  @PluginNotification("session-only")
  fun sessionOnly(session: LanguageServerSession) {
    invocations++
    receivedSession = session
  }

  @PluginNotification("payload-and-session")
  fun payloadAndSession(payload: TestPayload, session: LanguageServerSession) {
    invocations++
    receivedPayload = payload
    receivedSession = session
  }

  // These record before failing, so the arguments are genuinely consumed: a handler that throws
  // before touching what it was given would not tell these tests apart from one never invoked.
  @PluginNotification("session-only-throws")
  fun sessionOnlyThrows(session: LanguageServerSession): Nothing {
    receivedSession = session
    error("session-only handler failed")
  }

  @PluginNotification("payload-and-session-throws")
  fun payloadAndSessionThrows(payload: TestPayload, session: LanguageServerSession): Nothing {
    receivedPayload = payload
    receivedSession = session
    error("payload-and-session handler failed")
  }
}
