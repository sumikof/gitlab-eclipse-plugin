package com.gitlab.eclipse.lsp.plugins

import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.utils.logger
import com.google.gson.Gson
import java.util.concurrent.CompletableFuture

class PluginMessageService(private val registry: PluginRegistry) {
  private val logger = logger<PluginMessageService>()

  fun dispatch(route: PluginMessageRoute, payload: Any?): CompletableFuture<Any?> {
    return CompletableFuture.supplyAsync {
      val handler = registry[route]
        ?: return@supplyAsync logger.warn("No plugin registered for $route. Skipping.")

      if (handler.type == null && payload == null) {
        return@supplyAsync handler.handle.apply(null)
      } else if (handler.type != null && payload != null) {
        val argument = try {
          Gson().fromJson(Gson().toJsonTree(payload), handler.type)
        } catch (e: Throwable) {
          return@supplyAsync logger.warn("Could not parse payload ($payload) for $route. Skipping.", e)
        }

        return@supplyAsync try {
          handler.handle.apply(argument)
        } catch (e: Throwable) {
          return@supplyAsync logger.error(e.cause?.message, e.cause)
        }
      } else {
        return@supplyAsync logger.warn("Could handle message for $route with payload ($payload). Skipping.")
      }
    }
  }
}
