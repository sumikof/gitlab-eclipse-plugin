package com.gitlab.eclipse.lsp.plugins

import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.plugins.utils.PluginMessageRoute
import com.gitlab.eclipse.utils.logger
import com.google.gson.Gson
import java.util.concurrent.CompletableFuture

class PluginMessageService(private val registry: PluginRegistry) {
  private val logger = logger<PluginMessageService>()

  /**
   * @param session the connection the message was sent from. Dispatch hops to another thread, so by
   *   the time a handler runs the current connection may already be a different one; this is the
   *   sender, not whichever connection happens to be current on arrival. Null when the caller has no
   *   connection to name.
   */
  fun dispatch(
    route: PluginMessageRoute,
    payload: Any?,
    session: LanguageServerSession?
  ): CompletableFuture<Any?> {
    return CompletableFuture.supplyAsync {
      val handler = registry[route]
        ?: return@supplyAsync logger.warn("No plugin registered for $route. Skipping.")

      if (handler.type == null && payload == null) {
        return@supplyAsync handler.handle.apply(null, session)
      } else if (handler.type != null && payload != null) {
        val argument = try {
          Gson().fromJson(Gson().toJsonTree(payload), handler.type)
        } catch (e: Throwable) {
          return@supplyAsync logger.warn("Could not parse payload ($payload) for $route. Skipping.", e)
        }

        return@supplyAsync try {
          handler.handle.apply(argument, session)
        } catch (e: Throwable) {
          return@supplyAsync logger.error(e.cause?.message, e.cause)
        }
      } else {
        return@supplyAsync logger.warn("Could handle message for $route with payload ($payload). Skipping.")
      }
    }
  }
}
