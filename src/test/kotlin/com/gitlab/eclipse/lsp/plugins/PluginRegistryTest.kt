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

    it("does not invoke a handler that asks for a session when the message names none") {
      val controller = SessionAwareTestController()
      val route = PluginMessageRoute("session-aware", PluginMessageType.NOTIFICATION, "session-only")

      serviceFor(controller).dispatch(route, null, session = null).get()

      controller.invocations shouldBe 0
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
}
