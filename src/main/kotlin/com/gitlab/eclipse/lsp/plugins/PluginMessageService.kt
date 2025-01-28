package com.gitlab.eclipse.lsp.plugins

import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageHandler
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.utils.logger
import com.google.gson.Gson
import java.util.concurrent.CompletableFuture

class PluginMessageService {
  private val logger = logger<PluginMessageService>()

  private val registry = mutableMapOf<PluginMessageRoute, PluginMessageHandler>()

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

  fun registerMessageHandler(route: PluginMessageRoute, handler: PluginMessageHandler) {
    if (registry.containsKey(route)) {
      return logger.warn("Plugin route $route is already registered. Skipping.")
    }

    registry[route] = handler
  }
}
