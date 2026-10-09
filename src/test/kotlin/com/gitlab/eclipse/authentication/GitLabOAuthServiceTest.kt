package com.gitlab.eclipse.authentication

import com.github.scribejava.core.httpclient.jdk.JDKHttpClientConfig
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeTypeOf
import io.mockk.*
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle
import java.awt.Desktop
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.StandardCharsets

@Suppress("IgnoredReturnValue")
class GitLabOAuthServiceTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  beforeSpec {
    mockkStatic(Desktop::class)
  }

  afterSpec {
    unmockkAll()
  }
  var gitLabOAuthService = spyk(GitLabOAuthService())

  describe("startOAuthFlow") {
    it("should not attempt to open browser when desktop is not supported") {
      gitLabOAuthService = spyk(GitLabOAuthService())
      val desktopMock = mockk<Desktop>()
      every { Desktop.isDesktopSupported() } returns false
      every { Desktop.getDesktop() } returns desktopMock

      gitLabOAuthService.startOAuthFlow()

      verify(exactly = 0) {
        Desktop.getDesktop()
      }
    }

    it("should open the browser with the correct authorization URL") {
      val desktopMock = mockk<Desktop>()
      every { Desktop.isDesktopSupported() } returns true
      every { Desktop.getDesktop() } returns desktopMock
      every { desktopMock.browse(any()) } just Runs

      val serverMock = mockk<OAuthCallbackServer>()
      every { serverMock.start() } just Runs
      every { gitLabOAuthService.createServer(any()) } returns serverMock

      gitLabOAuthService.startOAuthFlow()

      verify {
        desktopMock.browse(
          match<URI> { uri ->
            uri.toString().startsWith("https://gitlab.com/oauth/authorize") &&
              uri.toString().contains("client_id=") &&
              uri.toString()
                .contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A63343%2Fapi%2Foauth%2Fgitlab%2Fauthorization") &&
              uri.toString().contains("code_challenge=") &&
              uri.toString().contains("code_challenge_method=S256")
          }
        )
        serverMock.start()
      }
    }
  }

  // Real local server sockets stand in for the token endpoint instead of reflecting the private
  // `oauthService`/`gson` fields: the behaviour under test (timeouts, response classification) is
  // in the HTTP stack itself, which a field-swapped mock cannot exercise.
  describe("refreshToken") {
    val currentRefreshToken = "current-refresh-token-value"

    it("T1: returns Transient within the read timeout when the endpoint never responds") {
      startFakeTokenEndpoint(rawResponse = null).use { serverSocket ->
        val service = testOAuthService(serverSocket, connectTimeoutMs = 300, readTimeoutMs = 300)

        val startedAt = System.currentTimeMillis()
        val outcome = service.refreshToken(currentRefreshToken)
        val elapsedMs = System.currentTimeMillis() - startedAt

        (elapsedMs < 2_000L) shouldBe true
        outcome.shouldBeTypeOf<RefreshOutcome.Transient>()
        (outcome as RefreshOutcome.Transient).reason shouldBe "SocketTimeoutException"
      }
    }

    it("T1b: restores the interrupt flag and classifies InterruptedException as Transient") {
      val service = GitLabOAuthService()
      var outcome: RefreshOutcome? = null
      var wasInterrupted = false

      val thread = Thread {
        outcome = service.classifyFailure(InterruptedException())
        wasInterrupted = Thread.currentThread().isInterrupted
      }
      thread.start()
      thread.join()

      outcome shouldBe RefreshOutcome.Transient("InterruptedException")
      wasInterrupted shouldBe true
    }

    it("T2: classifies invalid_grant, invalid_client and unauthorized_client as Rejected") {
      listOf("invalid_grant", "invalid_client", "unauthorized_client").forEach { errorCode ->
        val body = """{"error":"$errorCode","error_description":"x"}"""
        startFakeTokenEndpoint(rawHttpResponse(400, "Bad Request", body)).use { serverSocket ->
          val service = testOAuthService(serverSocket)

          val outcome = service.refreshToken(currentRefreshToken)

          outcome shouldBe RefreshOutcome.Rejected(errorCode)
        }
      }
    }

    it("T3: classifies non-JSON 5xx, excluded errors and empty bodies as Transient") {
      val cases = listOf(
        rawHttpResponse(502, "Bad Gateway", "<html>bad gateway</html>"),
        rawHttpResponse(400, "Bad Request", """{"error":"invalid_request"}"""),
        rawHttpResponse(500, "Internal Server Error", """{"error":"invalid_grant"}"""),
        rawHttpResponse(503, "Service Unavailable", ""),
      )

      cases.forEach { rawResponse ->
        startFakeTokenEndpoint(rawResponse).use { serverSocket ->
          val service = testOAuthService(serverSocket)

          val outcome = service.refreshToken(currentRefreshToken)

          outcome.shouldBeTypeOf<RefreshOutcome.Transient>()
        }
      }
    }

    it("T4: returns Refreshed with the parsed token on 200") {
      val createdAt = 1_234_567_890L
      val body = """{"access_token":"new-access-token-value","refresh_token":"new-refresh-token-value",""" +
        """"expires_in":7200,"created_at":$createdAt}"""
      startFakeTokenEndpoint(rawHttpResponse(200, "OK", body)).use { serverSocket ->
        val service = testOAuthService(serverSocket)

        val outcome = service.refreshToken(currentRefreshToken)

        outcome shouldBe RefreshOutcome.Refreshed(
          GitLabAuthorizationToken("new-access-token-value", "new-refresh-token-value", 7200, createdAt)
        )
      }
    }

    it("T5: never writes the access token, refresh token or sent token to stdout or the platform log") {
      val newAccessToken = "secret-access-token-marker"
      val newRefreshToken = "secret-refresh-token-marker"
      val sentRefreshToken = "secret-sent-refresh-token-marker"
      val createdAt = 1_234_567_890L
      val body = """{"access_token":"$newAccessToken","refresh_token":"$newRefreshToken",""" +
        """"expires_in":7200,"created_at":$createdAt}"""

      val logMock = mockk<ILog>(relaxUnitFun = true)
      val infoMessages = mutableListOf<String>()
      every { Platform.getLog(any<Bundle>()) } returns logMock
      every { Platform.getLog(any<Class<*>>()) } returns logMock
      every { logMock.info(capture(infoMessages)) } just Runs

      val originalOut = System.out
      val captured = ByteArrayOutputStream()
      System.setOut(PrintStream(captured))

      try {
        startFakeTokenEndpoint(rawHttpResponse(200, "OK", body)).use { serverSocket ->
          val service = testOAuthService(serverSocket)
          val outcome = service.refreshToken(sentRefreshToken)
          outcome.shouldBeTypeOf<RefreshOutcome.Refreshed>()
        }
      } finally {
        System.setOut(originalOut)
      }

      val stdout = captured.toByteArray().toString(StandardCharsets.UTF_8)
      stdout shouldNotContain newAccessToken
      stdout shouldNotContain newRefreshToken
      stdout shouldNotContain sentRefreshToken

      infoMessages.forEach { message ->
        message shouldNotContain newAccessToken
        message shouldNotContain newRefreshToken
        message shouldNotContain sentRefreshToken
      }
      verify(exactly = 0) { logMock.error(any(), any()) }
    }
  }
})

private fun testOAuthService(
  serverSocket: ServerSocket,
  connectTimeoutMs: Int = 500,
  readTimeoutMs: Int = 500,
): GitLabOAuthService =
  GitLabOAuthService(
    tokenEndpoint = "http://127.0.0.1:${serverSocket.localPort}/oauth/token",
    httpClientConfig = JDKHttpClientConfig.defaultConfig()
      .withConnectTimeout(connectTimeoutMs)
      .withReadTimeout(readTimeoutMs),
  )

/**
 * Opens a raw server socket and, on the first connection, either sends [rawResponse] verbatim and
 * closes the connection, or — when null — accepts the connection and never answers, to exercise the
 * client's read timeout (T1).
 */
private fun startFakeTokenEndpoint(rawResponse: String?): ServerSocket {
  val serverSocket = ServerSocket(0)
  val thread = Thread {
    try {
      serverSocket.accept().use { socket ->
        if (rawResponse == null) {
          Thread.sleep(5_000)
        } else {
          val reader = socket.getInputStream().bufferedReader()
          // Drain the request headers up to (and including) the blank line that ends them.
          while (!reader.readLine().isNullOrEmpty()) {
            // no-op: discard each header line
          }
          socket.getOutputStream().write(rawResponse.toByteArray(StandardCharsets.UTF_8))
          socket.getOutputStream().flush()
        }
      }
    } catch (_: Exception) {
      // The listening socket was closed by test cleanup, or the client disconnected first.
    }
  }
  thread.isDaemon = true
  thread.start()
  return serverSocket
}

private fun rawHttpResponse(status: Int, reason: String, body: String): String {
  val bodyBytes = body.toByteArray(StandardCharsets.UTF_8).size
  return "HTTP/1.1 $status $reason\r\n" +
    "Content-Type: application/json\r\n" +
    "Content-Length: $bodyBytes\r\n" +
    "Connection: close\r\n" +
    "\r\n" +
    body
}
