@file:Suppress("FunctionOnlyReturningConstant")

package com.gitlab.eclipse.lsp.plugins

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.plugins.annotations.PluginNotification
import com.gitlab.eclipse.lsp.plugins.annotations.PluginRequest
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageType
import com.gitlab.eclipse.preferences.storage.CapturedLog
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.bind
import org.koin.dsl.module

class PluginMessageServiceTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  beforeEach {
    startKoin {
      modules(
        module {
          single { TestPluginController() } bind PluginController::class
          single { AnotherTestPluginController() } bind PluginController::class
          single { PluginRegistry(getAll()) }
          single { PluginMessageService(get()) }
        }
      )
    }
  }

  afterEach { stopKoin() }

  it("should register all controllers") {
    val route1 = PluginMessageRoute(pluginId = "test", type = PluginMessageType.REQUEST, method = "request")
    val route2 = PluginMessageRoute(pluginId = "another-test", type = PluginMessageType.REQUEST, method = "request")
    val payload = null

    val result1 = service<PluginMessageService>().dispatch(route1, payload, session = null).get()
    val result2 = service<PluginMessageService>().dispatch(route2, payload, session = null).get()

    result1 shouldBe 123
    result2 shouldBe 789
  }

  it("should correctly register request endpoint without payload") {
    val route = PluginMessageRoute(pluginId = "test", type = PluginMessageType.REQUEST, method = "request")
    val payload = null

    val result = service<PluginMessageService>().dispatch(route, payload, session = null).get()

    result shouldBe 123
  }

  it("should correctly register request endpoint with payload") {
    val route = PluginMessageRoute(pluginId = "test", type = PluginMessageType.REQUEST, method = "request-payload")
    val payload = JsonObject().apply { add("message", JsonPrimitive("Hello World!")) }

    val result = service<PluginMessageService>().dispatch(route, payload, session = null).get()

    result shouldBe "Hello World!"
  }

  it("should correctly register notification endpoint without payload") {
    val route = PluginMessageRoute(pluginId = "test", type = PluginMessageType.NOTIFICATION, method = "notification")
    val payload = null

    val result = service<PluginMessageService>().dispatch(route, payload, session = null).get()

    result shouldBe 456
  }

  it("should correctly register notification endpoint with payload") {
    val route = PluginMessageRoute(pluginId = "test", type = PluginMessageType.NOTIFICATION, method = "notif-payload")
    val payload = JsonObject().apply { add("message", JsonPrimitive("Hello World!")) }

    val result = service<PluginMessageService>().dispatch(route, payload, session = null).get()

    result shouldBe "Hello World!"
  }

  // Design §9: a payload may hold snippet or message text, so neither skip arm may print it.
  describe("log hygiene") {
    it("names the route but not the payload when the payload cannot be parsed") {
      val log = CapturedLog().apply { install() }
      val route = PluginMessageRoute(pluginId = "test", type = PluginMessageType.NOTIFICATION, method = "notif-payload")
      val payload = JsonPrimitive("SECRET-PAYLOAD-TEXT")

      service<PluginMessageService>().dispatch(route, payload, session = null).get()

      log.contains("notif-payload") shouldBe true
      log.contains("SECRET-PAYLOAD-TEXT") shouldBe false
    }

    it("names the route but not the payload when a payload-less handler is given one") {
      val log = CapturedLog().apply { install() }
      val route = PluginMessageRoute(pluginId = "test", type = PluginMessageType.NOTIFICATION, method = "notification")
      val payload = JsonObject().apply { add("message", JsonPrimitive("SECRET-PAYLOAD-TEXT")) }

      service<PluginMessageService>().dispatch(route, payload, session = null).get()

      log.lines shouldContain "Could not handle message for $route with an unexpected payload. Skipping."
      log.contains("SECRET-PAYLOAD-TEXT") shouldBe false
    }
  }
})

class TestPluginController : PluginController("test") {
  @PluginRequest("request")
  fun request() = 123

  @PluginRequest("request-payload")
  fun requestPayload(payload: TestPayload) = payload.message

  @PluginNotification("notification")
  fun notification() = 456

  @PluginNotification("notif-payload")
  fun notificationPayload(payload: TestPayload) = payload.message
}

class AnotherTestPluginController : PluginController("another-test") {
  @PluginRequest("request")
  fun request() = 789
}

data class TestPayload(val message: String)
