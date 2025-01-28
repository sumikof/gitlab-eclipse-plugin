package com.gitlab.eclipse.lsp.plugins

import com.gitlab.eclipse.lsp.plugins.annotations.PluginNotification
import com.gitlab.eclipse.lsp.plugins.annotations.PluginRequest
import com.gitlab.eclipse.lsp.plugins.annotations.PluginRoute
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageHandler
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageType
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.dsl.module
import java.lang.reflect.Method

fun pluginCommunicationModule(): Module {
  val service = PluginMessageService()
  val controllers = GlobalContext.get().getAll<PluginController>()

  controllers.forEach { controller ->
    val definition = controller.javaClass

    val pluginId = definition.getAnnotation(PluginRoute::class.java).pluginId

    val requests = definition.declaredMethods.filter { it.isAnnotationPresent(PluginRequest::class.java) }
    val notifications = definition.declaredMethods.filter { it.isAnnotationPresent(PluginNotification::class.java) }

    (requests + notifications).register(pluginId, controller, service)
  }

  return module {
    single<PluginMessageService> { service }
  }
}

private fun List<Method>.register(
  pluginId: String,
  controller: Any,
  service: PluginMessageService
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

  service.registerMessageHandler(route, handler)
}
