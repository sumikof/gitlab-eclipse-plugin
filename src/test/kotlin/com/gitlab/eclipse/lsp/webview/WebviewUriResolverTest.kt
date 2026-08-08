package com.gitlab.eclipse.lsp.webview

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerHandle
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.WebviewInfo
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val WEBVIEW_ID = "root/mcp"

class WebviewUriResolverTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val languageServer = mockk<GitLabLanguageServer>()
  val wrapper = mockk<GitLabLanguageServerWrapper>()
  val session = LanguageServerSession()
  val handle = LanguageServerHandle(languageServer, session)
  val resolver = WebviewUriResolver(wrapper)

  fun await(future: CompletableFuture<WebviewResolution>) = future.get(2, TimeUnit.SECONDS)

  beforeEach {
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

    it("resolves the first uri, carrying the session captured at request start rather than read at apply time") {
      val metadataFuture = CompletableFuture<List<WebviewInfo?>?>()
      every { languageServer.webviewMetadata() } returns metadataFuture

      // The request starts while `handle` (session) is current.
      val future = resolver.resolve(WEBVIEW_ID)

      // The Language Server restarts mid-flight: a new session becomes current before the
      // in-flight request's metadata future completes. §7.1a: the result must still carry
      // the session that was current when the request STARTED, not the one current now.
      val restartedHandle = LanguageServerHandle(mockk(), LanguageServerSession())
      every { wrapper.currentSnapshot } returns restartedHandle

      metadataFuture.complete(
        listOf(WebviewInfo(WEBVIEW_ID, "MCP", listOf("gitlab://webview/mcp/1", "gitlab://webview/mcp/2"))),
      )
      val result = await(future)

      result shouldBe WebviewResolution.Resolved(WEBVIEW_ID, "MCP", "gitlab://webview/mcp/1", session)
      (result as WebviewResolution.Resolved).session shouldBeSameInstanceAs session
    }

    it("never completes exceptionally even when webviewMetadata() itself throws") {
      every { languageServer.webviewMetadata() } throws RuntimeException("boom")

      val future = resolver.resolve(WEBVIEW_ID)

      future.isCompletedExceptionally shouldBe false
      await(future).shouldBeInstanceOf<WebviewResolution.Failed>()
    }
  }
})
