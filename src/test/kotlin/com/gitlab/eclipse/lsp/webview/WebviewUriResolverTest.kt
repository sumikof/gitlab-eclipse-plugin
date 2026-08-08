package com.gitlab.eclipse.lsp.webview

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerHandle
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.WebviewInfo
import com.google.gson.Gson
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val WEBVIEW_ID = "root/mcp"
private const val SECRET_URI = "gitlab://webview/mcp?uri=file:///home/alice/secret-project.yml"

/**
 * Builds a [WebviewInfo] the way lsp4j does — by handing the JSON to Gson, which allocates without
 * calling the constructor, so the declared non-null [WebviewInfo.title] / [WebviewInfo.uris] are
 * genuinely `null` at runtime. This is the deserializer on lsp4j's own path, not a stand-in for it.
 */
private fun webviewInfoWithNullFields(id: String): WebviewInfo {
  val info: WebviewInfo = Gson().fromJson("""{"id":"$id"}""", WebviewInfo::class.java)
  // The premise, checked rather than assumed: were a future Gson to fill these in, the test that
  // uses this fixture would keep passing while no longer exercising anything.
  val title: String? = info.title
  val uris: List<String>? = info.uris
  check(title == null && uris == null) { "Gson no longer leaves WebviewInfo's declared non-nulls null" }
  return info
}

class WebviewUriResolverTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val languageServer = mockk<GitLabLanguageServer>()
  val wrapper = mockk<GitLabLanguageServerWrapper>()
  val session = LanguageServerSession()
  val handle = LanguageServerHandle(languageServer, session)
  val resolver = WebviewUriResolver(wrapper)

  fun await(future: CompletableFuture<WebviewResolution>) = future.get(2, TimeUnit.SECONDS)

  beforeEach {
    // The verification-call history on `languageServer` persists across tests within the spec
    // (Kotest's default SingleInstance isolation shares the mock); clear it here so
    // `verify(exactly = ...)` below is never order-dependent on which test ran before it.
    // `answers = false` keeps the per-test `every { ... }` stubs untouched — only clears history.
    clearMocks(languageServer, answers = false)
    every { wrapper.currentSnapshot } returns handle
  }

  describe("resolve") {
    it("resolves LanguageServerUnavailable without touching the proxy when there is no current session") {
      every { wrapper.currentSnapshot } returns null

      val result = await(resolver.resolve(WEBVIEW_ID))

      result shouldBe WebviewResolution.LanguageServerUnavailable
      verify(exactly = 0) { languageServer.webviewMetadata() }
    }

    it("resolves LanguageServerUnavailable when webviewMetadata() itself returns a null future") {
      every { languageServer.webviewMetadata() } returns null

      val result = await(resolver.resolve(WEBVIEW_ID))

      result shouldBe WebviewResolution.LanguageServerUnavailable
    }

    it("resolves Failed with a TimeoutException when the metadata future never completes") {
      val neverCompletes = CompletableFuture<List<WebviewInfo?>?>()
      every { languageServer.webviewMetadata() } returns neverCompletes
      val shortTimeoutResolver = WebviewUriResolver(wrapper, timeoutMillis = 50L)

      val result = await(shortTimeoutResolver.resolve(WEBVIEW_ID))

      result.shouldBeInstanceOf<WebviewResolution.Failed>()
      (result as WebviewResolution.Failed).cause.shouldBeInstanceOf<TimeoutException>()
    }

    it("resolves Failed with the original cause when the metadata future completes exceptionally") {
      val explicitFailure = CompletableFuture<List<WebviewInfo?>?>()
      every { languageServer.webviewMetadata() } returns explicitFailure
      val cause = RuntimeException("language server exploded")

      val future = resolver.resolve(WEBVIEW_ID)
      explicitFailure.completeExceptionally(cause)
      val result = await(future)

      result.shouldBeInstanceOf<WebviewResolution.Failed>()
      (result as WebviewResolution.Failed).cause shouldBeSameInstanceAs cause
    }

    it("resolves NotAdvertised when the metadata future completes successfully with a null list") {
      every { languageServer.webviewMetadata() } returns CompletableFuture.completedFuture(null)

      val result = await(resolver.resolve(WEBVIEW_ID))

      result shouldBe WebviewResolution.NotAdvertised(WEBVIEW_ID)
    }

    it("resolves NotAdvertised when the id is not among the advertised webviews") {
      every { languageServer.webviewMetadata() } returns CompletableFuture.completedFuture(
        listOf(WebviewInfo("some-other-id", "Other", listOf("gitlab://webview/other"))),
      )

      val result = await(resolver.resolve(WEBVIEW_ID))

      result shouldBe WebviewResolution.NotAdvertised(WEBVIEW_ID)
    }

    it("skips null entries in the metadata list instead of throwing") {
      every { languageServer.webviewMetadata() } returns CompletableFuture.completedFuture(
        listOf(null, WebviewInfo("some-other-id", "Other", listOf("gitlab://webview/other"))),
      )

      val result = await(resolver.resolve(WEBVIEW_ID))

      result shouldBe WebviewResolution.NotAdvertised(WEBVIEW_ID)
    }

    it("resolves NoUri when the advertised entry has no uris") {
      every { languageServer.webviewMetadata() } returns CompletableFuture.completedFuture(
        listOf(WebviewInfo(WEBVIEW_ID, "MCP", emptyList())),
      )

      val result = await(resolver.resolve(WEBVIEW_ID))

      result shouldBe WebviewResolution.NoUri(WEBVIEW_ID)
    }

    it("resolves a known id to its title and first advertised uri") {
      every { languageServer.webviewMetadata() } returns CompletableFuture.completedFuture(
        listOf(WebviewInfo(WEBVIEW_ID, "MCP", listOf("gitlab://webview/mcp/1", "gitlab://webview/mcp/2"))),
      )

      val result = await(resolver.resolve(WEBVIEW_ID))

      result shouldBe WebviewResolution.Resolved(WEBVIEW_ID, "MCP", "gitlab://webview/mcp/1", session)
    }

    it("carries the session captured at request start, not the one current when the future completes") {
      val metadataFuture = CompletableFuture<List<WebviewInfo?>?>()
      every { languageServer.webviewMetadata() } returns metadataFuture

      // The request starts while `handle` (session) is current.
      val future = resolver.resolve(WEBVIEW_ID)

      // The Language Server restarts mid-flight: a new session becomes current before the
      // in-flight request's metadata future completes. §7.1a: the result must still carry
      // the session that was current when the request STARTED, not the one current now.
      every { wrapper.currentSnapshot } returns LanguageServerHandle(mockk(), LanguageServerSession())

      metadataFuture.complete(listOf(WebviewInfo(WEBVIEW_ID, "MCP", listOf("gitlab://webview/mcp/1"))))
      val result = await(future)

      (result as WebviewResolution.Resolved).session shouldBeSameInstanceAs session
    }

    it("never completes exceptionally even when webviewMetadata() itself throws") {
      every { languageServer.webviewMetadata() } throws RuntimeException("boom")

      val future = resolver.resolve(WEBVIEW_ID)

      future.isCompletedExceptionally shouldBe false
      await(future).shouldBeInstanceOf<WebviewResolution.Failed>()
    }

    it("resolves Failed instead of hanging when the advertised entry has Gson-nulled fields") {
      // lsp4j deserializes WebviewInfo with Gson, which allocates without calling the
      // constructor: title/uris can be null at runtime despite their non-null declared type.
      // A6 must hold even then — the returned future must complete (with Failed), not hang.
      every { languageServer.webviewMetadata() } returns CompletableFuture.completedFuture(
        listOf(webviewInfoWithNullFields(WEBVIEW_ID)),
      )

      val result = await(resolver.resolve(WEBVIEW_ID))

      result.shouldBeInstanceOf<WebviewResolution.Failed>()
    }
  }

  // Design §17. Same class of pin as `WebviewEditorInputTest`'s two, on the types design §7.1
  // leaves holding an advertised uri and an exception. Interpolation is the shape a leak takes.
  describe("WebviewResolution's string form") {
    it("keeps the advertised uri out of Resolved's") {
      val rendered = "${WebviewResolution.Resolved(WEBVIEW_ID, "MCP", SECRET_URI, session)}"

      rendered shouldNotContain "secret-project"
      rendered shouldBe "WebviewResolution.Resolved($WEBVIEW_ID)"
    }

    // The generated form calls `cause.toString()`, which is the class name *and* the message.
    it("keeps the cause's message out of Failed's, and its type in") {
      val rendered = "${WebviewResolution.Failed(IllegalStateException(SECRET_URI))}"

      rendered shouldNotContain "secret-project"
      rendered shouldBe "WebviewResolution.Failed(type=java.lang.IllegalStateException)"
    }

    it("says so when Failed carries no cause at all") {
      "${WebviewResolution.Failed(null)}" shouldBe "WebviewResolution.Failed(type=null)"
    }
  }
})
