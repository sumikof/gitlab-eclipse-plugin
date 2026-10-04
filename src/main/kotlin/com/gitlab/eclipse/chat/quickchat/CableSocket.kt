package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.inject.service
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** The send side of one ActionCable WebSocket (design `quick-chat-streaming` §11.1). */
interface CableSocket {
  fun sendText(text: String)

  fun request(n: Long)

  fun abort()
}

/**
 * Receives the events of one ActionCable WebSocket (§11.1, §17). Called on transport threads:
 * implementations must not throw, block or touch the UI.
 */
interface CableListener {
  fun onOpen(socket: CableSocket)

  fun onText(data: CharSequence, last: Boolean)

  fun onClosed()

  fun onError(error: Throwable)
}

/** Opens an ActionCable WebSocket (§11.1, §18). */
fun interface CableSocketFactory {
  fun connect(endpoint: CableEndpoint, token: String, listener: CableListener): CompletableFuture<CableSocket>
}

/**
 * Opens the ActionCable WebSocket through the shared egress [HttpClient] so proxy and TLS settings
 * apply (§11.1, §18). The client is fetched on every [connect] so a settings change is picked up.
 * Only the subprotocol, `Authorization` and `Origin` are set (K-S4); the token is never logged.
 */
class JdkCableSocketFactory(
  private val httpClient: () -> HttpClient = { service<GitLabHttpClient>().rebuildIfNeeded() },
) : CableSocketFactory {
  override fun connect(
    endpoint: CableEndpoint,
    token: String,
    listener: CableListener,
  ): CompletableFuture<CableSocket> =
    httpClient()
      .newWebSocketBuilder()
      .subprotocols(SUBPROTOCOL)
      .header("Authorization", "Bearer $token")
      .header("Origin", endpoint.origin)
      .buildAsync(endpoint.uri, Adapter(listener))
      .thenApply { JdkCableSocket(it) }

  /**
   * Forwards JDK callbacks. `onOpen` deliberately does not call the JDK default `request(1)`: the
   * [CableListener] owns flow control (§15.3).
   */
  private class Adapter(private val listener: CableListener) : WebSocket.Listener {
    override fun onOpen(webSocket: WebSocket) {
      listener.onOpen(JdkCableSocket(webSocket))
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
      listener.onText(data, last)
      return null
    }

    override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
      // ActionCable JSON never sends binary frames; keep the flow going without reading them.
      webSocket.request(1)
      return null
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
      listener.onClosed()
      return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
      listener.onError(error)
    }
  }

  private class JdkCableSocket(private val webSocket: WebSocket) : CableSocket {
    override fun sendText(text: String) {
      webSocket.sendText(text, true)
    }

    override fun request(n: Long) {
      webSocket.request(n)
    }

    override fun abort() {
      webSocket.abort()
    }
  }

  private companion object {
    const val SUBPROTOCOL = "actioncable-v1-json"
  }
}
