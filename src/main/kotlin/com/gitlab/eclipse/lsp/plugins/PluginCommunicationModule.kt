package com.gitlab.eclipse.lsp.plugins

import com.gitlab.eclipse.lsp.plugins.annotations.PluginController
import com.gitlab.eclipse.lsp.plugins.annotations.PluginNotification
import com.gitlab.eclipse.lsp.plugins.annotations.PluginRequest
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageHandler
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageType
import org.reflections.Reflections
import java.lang.reflect.Method

class PluginCommunicationModule(pkgName: String = "com.gitlab.eclipse") {
  val service: PluginMessageService = PluginMessageService()

  init {
    val reflections = Reflections(pkgName)

    val controllers = reflections.getTypesAnnotatedWith(PluginController::class.java)
    controllers.forEach { controller ->
      val pluginId = controller.getAnnotation(PluginController::class.java).pluginId
      val instance = controller.getDeclaredConstructor().newInstance()

      val requests = controller.declaredMethods.filter { it.isAnnotationPresent(PluginRequest::class.java) }
      val notifications = controller.declaredMethods.filter { it.isAnnotationPresent(PluginNotification::class.java) }

      (requests + notifications).register(pluginId, instance)
    }
  }

  private fun List<Method>.register(pluginId: String, controller: Any) = forEach { method ->
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

    service.registerMessageHandler(route, handler)
  }
}
