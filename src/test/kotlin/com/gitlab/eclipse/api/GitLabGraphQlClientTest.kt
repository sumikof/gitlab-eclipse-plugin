package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.authentication.GitLabTokenProviderManager
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.io.IOException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow

private data class SamplePayload(val id: Int, val name: String)

private const val CUSTOM_TIMEOUT_SECONDS = 7L

/** Reads the actual bytes of an [HttpRequest]'s body publisher, not just its length. */
private fun HttpRequest.bodyText(): String {
  val publisher = bodyPublisher().get()
  val bytes = mutableListOf<Byte>()
  val done = CountDownLatch(1)
  publisher.subscribe(
    object : Flow.Subscriber<ByteBuffer> {
      override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)

      override fun onNext(item: ByteBuffer) {
        val chunk = ByteArray(item.remaining())
        item.get(chunk)
        bytes.addAll(chunk.toList())
      }

      override fun onError(throwable: Throwable) = done.countDown()

      override fun onComplete() = done.countDown()
    },
  )
  done.await()
  return String(bytes.toByteArray(), StandardCharsets.UTF_8)
}

class GitLabGraphQlClientTest : DescribeSpec({
  val http = mockk<GitLabHttpClient>()
  val tokens = mockk<GitLabTokenProviderManager>()
  val prefs = mockk<ScopedPreferenceStore>()
  val apiClient = GitLabApiClient(http, tokens, prefs)
  val client = GitLabGraphQlClient(http, apiClient)
  val gson = Gson()
  val timeout = Duration.ofSeconds(CUSTOM_TIMEOUT_SECONDS)

  val connection = ConnectionSnapshot(
    instanceUrl = "https://pinned.example.com/",
    token = "pinned-token",
    authFingerprint = "0123456789abcdef",
    configGeneration = 42L,
  )

  fun response(body: String, status: Int = 200, headers: Map<String, List<String>> = emptyMap()): HttpResponse<String> {
    val resp = mockk<HttpResponse<String>>()
    every { resp.statusCode() } returns status
    every { resp.body() } returns body
    every { resp.headers() } returns java.net.http.HttpHeaders.of(headers) { _, _ -> true }
    return resp
  }

  beforeEach {
    clearMocks(http, tokens, prefs)
    // Global settings deliberately DIFFERENT from the pinned connection: execute() must never
    // read them, otherwise a mid-session settings change misroutes the request / leaks the
    // wrong credential.
    every { tokens.getToken() } returns "global-token"
    every { prefs.getString(PreferenceConstants.GITLAB_INSTANCE_URL) } returns "https://global.example.com"
  }

  describe("execute - URI construction") {
    it("builds /api/graphql under a bare instance URL") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("""{"data":{"id":1,"name":"a"}}""")

      client.execute(
        "query",
        emptyMap(),
        SamplePayload::class.java,
        connection.copy(instanceUrl = "https://gitlab.example.com"),
        timeout,
      )

      captured.captured.uri().toString() shouldBe "https://gitlab.example.com/api/graphql"
    }

    it("does not double the slash when the instance URL already ends with one") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("""{"data":{"id":1,"name":"a"}}""")

      client.execute(
        "query",
        emptyMap(),
        SamplePayload::class.java,
        connection.copy(instanceUrl = "https://gitlab.example.com/"),
        timeout,
      )

      captured.captured.uri().toString() shouldBe "https://gitlab.example.com/api/graphql"
    }

    it("appends /api/graphql after a custom instance path") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("""{"data":{"id":1,"name":"a"}}""")

      client.execute(
        "query",
        emptyMap(),
        SamplePayload::class.java,
        connection.copy(instanceUrl = "https://example.com/gitlab/"),
        timeout,
      )

      captured.captured.uri().toString() shouldBe "https://example.com/gitlab/api/graphql"
    }

    it("never contains an /api/v4 segment") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("""{"data":{"id":1,"name":"a"}}""")

      client.execute("query", emptyMap(), SamplePayload::class.java, connection, timeout)

      captured.captured.uri().toString() shouldNotContain "/api/v4"
    }
  }

  describe("execute - pinning") {
    it("uses the connection's Bearer token and instance host, never the global prefs/token") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("""{"data":{"id":1,"name":"a"}}""")

      client.execute("query", emptyMap(), SamplePayload::class.java, connection, timeout)

      captured.captured.uri().host shouldBe "pinned.example.com"
      captured.captured.headers().firstValue("Authorization").get() shouldBe "Bearer pinned-token"
      verify(exactly = 0) { prefs.getString(any()) }
      verify(exactly = 0) { tokens.getToken() }
    }
  }

  describe("execute - request shape") {
    it("sends POST with Content-Type and Accept application/json") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("""{"data":{"id":1,"name":"a"}}""")

      client.execute("query", emptyMap(), SamplePayload::class.java, connection, timeout)

      captured.captured.method() shouldBe "POST"
      captured.captured.headers().firstValue("Content-Type").get() shouldBe "application/json"
      captured.captured.headers().firstValue("Accept").get() shouldBe "application/json"
    }

    it("serializes the body as an object with exactly query and variables, matching the input") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("""{"data":{"id":1,"name":"a"}}""")
      val document = "query { me { id } }"
      val variables = mapOf("first" to 10, "after" to "cursor-1")

      client.execute(document, variables, SamplePayload::class.java, connection, timeout)

      val parsedBody = gson.fromJson(captured.captured.bodyText(), JsonObject::class.java)
      parsedBody.keySet() shouldBe setOf("query", "variables")
      parsedBody.get("query").asString shouldBe document
      parsedBody.get("variables") shouldBe gson.toJsonTree(variables)
    }

    it("serializes an empty variables map as {}") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("""{"data":{"id":1,"name":"a"}}""")

      client.execute("query", emptyMap(), SamplePayload::class.java, connection, timeout)

      val parsedBody = gson.fromJson(captured.captured.bodyText(), JsonObject::class.java)
      parsedBody.getAsJsonObject("variables").keySet() shouldBe emptySet()
    }

    it("puts the caller-supplied timeout on the HttpRequest") {
      val captured = slot<HttpRequest>()
      every { http.send(capture(captured)) } returns response("""{"data":{"id":1,"name":"a"}}""")

      client.execute("query", emptyMap(), SamplePayload::class.java, connection, timeout)

      captured.captured.timeout().get() shouldBe timeout
    }
  }

  describe("execute - L1 transport errors") {
    it("throws GitLabApiException with status, body, and correlationId on HTTP 500") {
      every { http.send(any()) } returns
        response("boom", status = 500, headers = mapOf("x-request-id" to listOf("corr-500")))

      val exception = shouldThrow<GitLabApiException> {
        client.execute("query", emptyMap(), SamplePayload::class.java, connection, timeout)
      }

      exception.statusCode shouldBe 500
      exception.responseBody shouldBe "boom"
      exception.correlationId shouldBe "corr-500"
    }

    it("throws GitLabApiException on HTTP 401 without parsing the body as GraphQL") {
      every { http.send(any()) } returns response("not graphql json at all", status = 401)

      val exception = shouldThrow<GitLabApiException> {
        client.execute("query", emptyMap(), SamplePayload::class.java, connection, timeout)
      }

      exception.statusCode shouldBe 401
    }
  }

  describe("execute - L2 GraphQL errors") {
    it("throws GraphQlException with hasDataKey=false when errors present and data absent") {
      every { http.send(any()) } returns response("""{"errors":[{"message":"a"},{"message":"b"}]}""")

      val exception = shouldThrow<GraphQlException> {
        client.execute("query", emptyMap(), SamplePayload::class.java, connection, timeout)
      }

      exception.hasDataKey shouldBe false
      exception.messages shouldBe listOf("a", "b")
    }

    it("throws GraphQlException with hasDataKey=true when data is present but null") {
      every { http.send(any()) } returns response("""{"data":null,"errors":[{"message":"partial"}]}""")

      val exception = shouldThrow<GraphQlException> {
        client.execute("query", emptyMap(), SamplePayload::class.java, connection, timeout)
      }

      exception.hasDataKey shouldBe true
    }

    it("throws GraphQlException with hasDataKey=true when errors accompany usable data") {
      every { http.send(any()) } returns
        response("""{"data":{"id":1,"name":"a"},"errors":[{"message":"resolver failed"}]}""")

      val exception = shouldThrow<GraphQlException> {
        client.execute("query", emptyMap(), SamplePayload::class.java, connection, timeout)
      }

      exception.hasDataKey shouldBe true
    }

    it("carries the x-request-id header into GraphQlException.correlationId") {
      every { http.send(any()) } returns
        response("""{"errors":[{"message":"a"}]}""", headers = mapOf("x-request-id" to listOf("corr-graphql")))

      val exception = shouldThrow<GraphQlException> {
        client.execute("query", emptyMap(), SamplePayload::class.java, connection, timeout)
      }

      exception.correlationId shouldBe "corr-graphql"
    }

    it("treats an empty errors array as success and returns the deserialized data") {
      every { http.send(any()) } returns response("""{"data":{"id":1,"name":"a"},"errors":[]}""")

      val result = client.execute("query", emptyMap(), SamplePayload::class.java, connection, timeout)

      result shouldBe SamplePayload(1, "a")
    }
  }

  describe("execute - malformed and successful responses") {
    it("throws JsonSyntaxException when the body has neither data nor errors") {
      every { http.send(any()) } returns response("{}")

      shouldThrow<JsonSyntaxException> {
        client.execute("query", emptyMap(), SamplePayload::class.java, connection, timeout)
      }
    }

    it("throws JsonSyntaxException when data is JSON-null and there are no errors") {
      every { http.send(any()) } returns response("""{"data":null}""")

      shouldThrow<JsonSyntaxException> {
        client.execute("query", emptyMap(), SamplePayload::class.java, connection, timeout)
      }
    }

    it("throws JsonSyntaxException when the body is not JSON at all") {
      every { http.send(any()) } returns response("not json")

      shouldThrow<JsonSyntaxException> {
        client.execute("query", emptyMap(), SamplePayload::class.java, connection, timeout)
      }
    }

    it("returns a populated instance of type on a well-formed data response") {
      every { http.send(any()) } returns response("""{"data":{"id":42,"name":"widget"}}""")

      val result = client.execute("query", emptyMap(), SamplePayload::class.java, connection, timeout)

      result shouldBe SamplePayload(42, "widget")
    }
  }

  describe("execute - propagation") {
    it("propagates HttpTimeoutException from httpClient.send unchanged") {
      every { http.send(any()) } throws HttpTimeoutException("request timed out")

      shouldThrow<HttpTimeoutException> {
        client.execute("query", emptyMap(), SamplePayload::class.java, connection, timeout)
      }
    }

    it("propagates IOException from httpClient.send unchanged") {
      every { http.send(any()) } throws IOException("connection reset")

      shouldThrow<IOException> {
        client.execute("query", emptyMap(), SamplePayload::class.java, connection, timeout)
      }
    }
  }

  describe("captureConnection") {
    it("delegates to apiClient.captureConnection() exactly once and returns its result") {
      val mockApiClient = mockk<GitLabApiClient>()
      val delegatingClient = GitLabGraphQlClient(http, mockApiClient)
      every { mockApiClient.captureConnection() } returns connection

      val result = delegatingClient.captureConnection()

      result shouldBe connection
      verify(exactly = 1) { mockApiClient.captureConnection() }
    }
  }
})
