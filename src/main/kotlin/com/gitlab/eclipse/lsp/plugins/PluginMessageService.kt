package com.gitlab.eclipse.lsp.plugins

import com.gitlab.eclipse.lsp.plugins.annotations.PluginNotification
import com.gitlab.eclipse.lsp.plugins.annotations.PluginRequest
import com.gitlab.eclipse.lsp.plugins.annotations.PluginRoute
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageHandler
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageType
import com.gitlab.eclipse.utils.logger
import com.google.gson.Gson
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture

class PluginMessageService(
  controllers: List<PluginController>
) {

  private val logger = logger<PluginMessageService>()
  private val registry = mutableMapOf<PluginMessageRoute, PluginMessageHandler>()

  init {
    controllers.forEach { controller ->
      val definition = controller.javaClass

      val pluginId = definition.getAnnotation(PluginRoute::class.java).pluginId

      val requests = definition.declaredMethods.filter { it.isAnnotationPresent(PluginRequest::class.java) }
      val notifications = definition.declaredMethods.filter { it.isAnnotationPresent(PluginNotification::class.java) }

      (requests + notifications).register(pluginId, controller)
    }
  }

  private fun List<Method>.register(
    pluginId: String,
    controller: Any,
  ) = forEach { method ->
    if (method.parameterCount > 1) {
      error("Method ${method.name} is not a valid request handler, multiple arguments found.")
    }

    val route = if (method.isAnnotationPresent(PluginRequest::class.java)) {
      val type = method.getAnnotation(PluginRequest::class.java).type
      PluginMessageRoute(pluginId, PluginMessageType.REQUEST, type)
    } else {
      val type = method.getAnnotation(PluginNotification::class.java).type
      PluginMessageRoute(pluginId, PluginMessageType.NOTIFICATION, type)
    }

    val payloadType = method.parameters.firstOrNull()?.type
    val handler = PluginMessageHandler(payloadType) { payload ->
      when {
        payload == null -> method.invoke(controller)
        else -> method.invoke(controller, payload)
      }
    }

    registerMessageHandler(route, handler)
  }

  private fun registerMessageHandler(route: PluginMessageRoute, handler: PluginMessageHandler) {
    if (registry.containsKey(route)) {
      return logger.warn("Plugin route $route is already registered. Skipping.")
    }

    registry[route] = handler
  }

  fun dispatch(route: PluginMessageRoute, payload: Any?): CompletableFuture<Any?> {
    return CompletableFuture.supplyAsync {
      val handler = registry[route]
        ?: return@supplyAsync logger.warn("No plugin registered for $route. Skipping.")

      if (handler.type == null && payload == null) {
        return@supplyAsync handler.handle.apply(null)
      } else if (handler.type != null && payload != null) {
        try {
          val argument = Gson().fromJson(Gson().toJsonTree(payload), handler.type)
          return@supplyAsync handler.handle.apply(argument)
        } catch (e: Throwable) {
          return@supplyAsync logger.warn("Could not parse payload ($payload) for $route. Skipping.", e)
        }
      } else {
        return@supplyAsync logger.warn("Could handle message for $route with payload ($payload). Skipping.")
      }
    }
  }
}
