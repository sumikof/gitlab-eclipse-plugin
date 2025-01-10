package com.gitlab.eclipse.lsp.plugins

import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageHandler
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.utils.logger
import com.google.gson.Gson
import com.google.gson.JsonElement
import org.eclipse.core.runtime.Platform
import org.osgi.framework.FrameworkUtil
import java.util.concurrent.CompletableFuture

class PluginMessageService {
  private val logger = logger<PluginMessageService>()

  private val registry = mutableMapOf<PluginMessageRoute, PluginMessageHandler>()

  fun dispatch(route: PluginMessageRoute, payload: JsonElement?): CompletableFuture<Any?> {
    return CompletableFuture.supplyAsync {
      val handler = registry[route]
        ?: return@supplyAsync logger.warn("No plugin registered for $route. Skipping.")

      if (handler.type == null && payload == null) {
        return@supplyAsync handler.handle.apply(null)
      } else if (handler.type != null && payload != null) {
        try {
          val argument = Gson().fromJson(payload.asJsonObject, handler.type)
          return@supplyAsync handler.handle.apply(argument)
        } catch (e: Exception) {
          return@supplyAsync logger.warn("Could not parse payload ($payload) for $route. Skipping.", e)
        }
      } else {
        return@supplyAsync logger.warn("Could handle message for $route with payload ($payload). Skipping.")
      }
    }
  }

  fun registerMessageHandler(route: PluginMessageRoute, handler: PluginMessageHandler) {
    if (registry.containsKey(route)) {
      return logger.warn("Plugin route $route is already registered. Skipping.")
    }

    registry[route] = handler
  }
}
