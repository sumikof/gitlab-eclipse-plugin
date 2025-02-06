package com.gitlab.eclipse.lsp.plugins

import com.gitlab.eclipse.lsp.plugins.annotations.PluginNotification
import com.gitlab.eclipse.lsp.plugins.annotations.PluginRequest
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageHandler
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageType
import com.gitlab.eclipse.utils.logger
import java.lang.reflect.Method

class PluginRegistry(controllers: List<PluginController>) {
  private val logger = logger<PluginRegistry>()
  private val registry = mutableMapOf<PluginMessageRoute, PluginMessageHandler>()

  init {
    controllers.forEach { controller ->
      val definition = controller.javaClass

      val requests = definition.declaredMethods.filter { it.isAnnotationPresent(PluginRequest::class.java) }
      val notifications = definition.declaredMethods.filter { it.isAnnotationPresent(PluginNotification::class.java) }

      (requests + notifications).register(controller)
    }
  }

  private fun List<Method>.register(controller: PluginController) = forEach { method ->
    if (method.parameterCount > 1) {
      error("Method ${method.name} is not a valid request handler, multiple arguments found.")
    }

    val route = if (method.isAnnotationPresent(PluginRequest::class.java)) {
      val type = method.getAnnotation(PluginRequest::class.java).type
      PluginMessageRoute(controller.pluginId, PluginMessageType.REQUEST, type)
    } else {
      val type = method.getAnnotation(PluginNotification::class.java).type
      PluginMessageRoute(controller.pluginId, PluginMessageType.NOTIFICATION, type)
    }

    val payloadType = method.parameters.firstOrNull()?.type
    val handler = PluginMessageHandler(payloadType) { payload ->
      when {
        payload == null -> method.invoke(controller)
        else -> method.invoke(controller, payload)
      }
    }

    if (registry.containsKey(route)) {
      return logger.warn("Plugin route $route is already registered. Skipping.")
    }

    registry[route] = handler
  }

  operator fun get(route: PluginMessageRoute) = registry[route]
}
