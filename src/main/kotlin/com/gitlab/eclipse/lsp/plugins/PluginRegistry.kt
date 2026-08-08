package com.gitlab.eclipse.lsp.plugins

import com.gitlab.eclipse.lsp.LanguageServerSession
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
    // A handler asks for the connection a message came from by declaring it as its last parameter,
    // by type and never by name. Everything before that parameter is the payload, so the count that
    // has always been constrained — and the type the payload is parsed into — are both taken from
    // the parameters that remain once the session is set aside.
    val acceptsSession = method.parameters.lastOrNull()?.type == LanguageServerSession::class.java
    val payloadParameters = method.parameters.let { if (acceptsSession) it.dropLast(1) else it.toList() }

    if (payloadParameters.size > 1) {
      error("Method ${method.name} is not a valid request handler, multiple arguments found.")
    }

    val route = if (method.isAnnotationPresent(PluginRequest::class.java)) {
      val type = method.getAnnotation(PluginRequest::class.java).type
      PluginMessageRoute(controller.pluginId, PluginMessageType.REQUEST, type)
    } else {
      val type = method.getAnnotation(PluginNotification::class.java).type
      PluginMessageRoute(controller.pluginId, PluginMessageType.NOTIFICATION, type)
    }

    val payloadType = payloadParameters.firstOrNull()?.type
    val handler = PluginMessageHandler(payloadType) { payload, session ->
      when {
        // A handler that asked which connection sent this cannot be handed a substitute: reading
        // "whichever connection is current" here is the confusion the parameter exists to remove.
        // So the message is dropped, the way an unparseable payload is dropped.
        acceptsSession && session == null ->
          logger.warn("Message for $route names no originating session, which ${method.name} requires. Skipping.")

        payload == null && !acceptsSession -> method.invoke(controller)
        payload == null -> method.invoke(controller, session)
        !acceptsSession -> method.invoke(controller, payload)
        else -> method.invoke(controller, payload, session)
      }
    }

    if (registry.containsKey(route)) {
      return logger.warn("Plugin route $route is already registered. Skipping.")
    }

    registry[route] = handler
  }

  operator fun get(route: PluginMessageRoute) = registry[route]
}
