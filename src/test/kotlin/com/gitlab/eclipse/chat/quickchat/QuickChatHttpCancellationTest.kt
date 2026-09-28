package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.api.GitLabGraphQlClient
import com.gitlab.eclipse.api.http.EgressConfigSnapshot
import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.api.http.GitLabHttpClientFactory
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.navigation.ProjectResolution
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A32 / E12: cancelling the coroutine of a send stops an HTTP request that is stuck on the wire,
 * through the real GraphQL → [GitLabHttpClient] → JDK `HttpClient.send` path, with a loopback server
 * that (1) never answers, or (2) sends the headers and then stalls the body — where the JDK's own
 * request timeout does not help (E12).
 */
class QuickChatHttpCancellationTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val stall = CountDownLatch(1)
  val pool = Executors.newCachedThreadPool()
  val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
    executor = pool
    start()
  }

  fun handle(path: String, respond: (HttpExchange) -> Unit): CountDownLatch {
    val latch = CountDownLatch(1)
    server.createContext(path) { exchange ->
      exchange.requestBody.readAllBytes()
      latch.countDown()
      try {
        respond(exchange)
        stall.await(1, TimeUnit.MINUTES)
      } finally {
        exchange.close()
      }
    }
    return latch
  }

  val realFactory = GitLabHttpClientFactory(mockk(), mockk(), mockk())
  val factory = mockk<GitLabHttpClientFactory> {
    every { currentSnapshot() } returns EgressConfigSnapshot(false, null, null, null, null)
    every { create(any()) } answers { realFactory.create(firstArg()) }
  }
  val httpClient = GitLabHttpClient(factory)
  val api = GraphQlQuickChatApi(GitLabGraphQlClient(httpClient))

  val background = BackgroundScope()

  afterSpec {
    background.close()
    stall.countDown()
    server.stop(0)
    pool.shutdownNow()
    httpClient.close()
  }

  suspend fun cancelsPromptly(prefix: String, receivedLatch: CountDownLatch) {
    val connections = FakeConnections(snapshot("http://127.0.0.1:${server.address.port}$prefix"))
    val service = QuickChatService(
      api,
      connections,
      QuickChatPreflight(api) { ProjectResolution.NotInRepository },
      QuickChatPoller(api, connections),
      requestTimeout = 1.minutes, // far beyond the test: only the interrupt can end the request
    )
    // Bound with a passed preflight for a loose file: the send goes straight to aiAction.
    val binding = ConversationBinding(
      connections.current.instanceUrl,
      Preflight(null, ProjectIdentity.NOT_IN_REPOSITORY),
      null,
    )
    val gate = SendGate()
    val deadline = System.nanoTime() + 2.minutes.inWholeNanoseconds
    val request = QuickChatRequest(QuickChatContext("q", null), null, binding, deadline, gate)
    val job = background.scope.async { service.ask(request) }
    receivedLatch.await(10, TimeUnit.SECONDS) shouldBe true
    gate.state shouldBe SendGate.State.Sending
    val started = System.nanoTime()
    job.cancel()
    withTimeout(5.seconds) { job.join() }
    job.isCancelled shouldBe true
    (System.nanoTime() - started) shouldBeLessThan 5.seconds.inWholeNanoseconds
  }

  it("stops a request whose response never starts") {
    val latch = handle("/silent/api/graphql") { /* never send headers */ }
    cancelsPromptly("/silent", latch)
  }
  it("stops a request whose body stalls after the headers") {
    val latch = handle("/stalled/api/graphql") { exchange ->
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, 0) // chunked: the body never ends
      exchange.responseBody.write("{\"data\":".toByteArray())
      exchange.responseBody.flush()
    }
    cancelsPromptly("/stalled", latch)
  }
})
