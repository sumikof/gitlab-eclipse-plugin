package com.gitlab.eclipse.lsp.webview

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.knowledgegraph.KnowledgeGraphState
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerHandle
import com.gitlab.eclipse.lsp.LanguageServerSession
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle
import java.util.concurrent.CompletableFuture

private const val WEBVIEW_ID = "root/flow"
private const val BASE_URI = "https://gitlab.example.com/root/flow"

class WebviewLoadCoordinatorTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  /** Runs queued work immediately: design §15 says headless tests drive this single-threaded. */
  val runInline: (() -> Unit) -> Unit = { it() }

  fun wrapperWith(session: LanguageServerSession?): GitLabLanguageServerWrapper {
    val wrapper = mockk<GitLabLanguageServerWrapper>()
    every { wrapper.currentSnapshot } returns
      session?.let { LanguageServerHandle(mockk<GitLabLanguageServer>(), it, 0L) }
    return wrapper
  }

  fun resolverReturning(vararg futures: CompletableFuture<WebviewResolution>): WebviewUriResolver {
    val resolver = mockk<WebviewUriResolver>()
    every { resolver.resolve(WEBVIEW_ID) } returnsMany futures.toList()
    return resolver
  }

  fun await(future: CompletableFuture<WebviewLoadCoordinator.Outcome>) = future.getNow(null)

  describe("load") {
    it("turns a resolution that belongs to the current session into Show") {
      val session = LanguageServerSession()
      val resolution = CompletableFuture.completedFuture(
        WebviewResolution.Resolved(WEBVIEW_ID, "Flow Builder", BASE_URI, session) as WebviewResolution,
      )
      val coordinator = WebviewLoadCoordinator(resolverReturning(resolution), wrapperWith(session), runInline)

      val outcome = await(coordinator.load(WEBVIEW_ID, emptyMap()))

      outcome shouldBe WebviewLoadCoordinator.Outcome.Show(BASE_URI, "Flow Builder", session)
    }

    it("appends the query parameters to the advertised uri") {
      val session = LanguageServerSession()
      val resolution = CompletableFuture.completedFuture(
        WebviewResolution.Resolved(WEBVIEW_ID, "Flow Builder", BASE_URI, session) as WebviewResolution,
      )
      val coordinator = WebviewLoadCoordinator(resolverReturning(resolution), wrapperWith(session), runInline)

      val outcome = await(coordinator.load(WEBVIEW_ID, mapOf("uri" to "file:///a b.yml")))

      (outcome as WebviewLoadCoordinator.Outcome.Show).url shouldBe "$BASE_URI?uri=file%3A%2F%2F%2Fa%20b.yml"
    }

    // A16 (design §21): the generations are deliberately left equal — only the session differs.
    it("discards a resolution whose captured session is no longer the current one") {
      val staleSession = LanguageServerSession()
      val resolution = CompletableFuture.completedFuture(
        WebviewResolution.Resolved(WEBVIEW_ID, "Flow Builder", BASE_URI, staleSession) as WebviewResolution,
      )
      val coordinator = WebviewLoadCoordinator(
        resolverReturning(resolution),
        wrapperWith(LanguageServerSession()),
        runInline,
      )

      await(coordinator.load(WEBVIEW_ID, emptyMap())) shouldBe WebviewLoadCoordinator.Outcome.SessionChanged
    }

    it("discards a resolution whose session is gone entirely") {
      val staleSession = LanguageServerSession()
      val resolution = CompletableFuture.completedFuture(
        WebviewResolution.Resolved(WEBVIEW_ID, "Flow Builder", BASE_URI, staleSession) as WebviewResolution,
      )
      val coordinator = WebviewLoadCoordinator(resolverReturning(resolution), wrapperWith(null), runInline)

      await(coordinator.load(WEBVIEW_ID, emptyMap())) shouldBe WebviewLoadCoordinator.Outcome.SessionChanged
    }

    it("reports a load that a newer load has overtaken as Superseded") {
      val session = LanguageServerSession()
      val first = CompletableFuture<WebviewResolution>()
      val second = CompletableFuture<WebviewResolution>()
      val coordinator = WebviewLoadCoordinator(resolverReturning(first, second), wrapperWith(session), runInline)
      val resolved = WebviewResolution.Resolved(WEBVIEW_ID, "Flow Builder", BASE_URI, session)

      val firstOutcome = coordinator.load(WEBVIEW_ID, emptyMap())
      val secondOutcome = coordinator.load(WEBVIEW_ID, emptyMap())
      second.complete(resolved)
      first.complete(resolved)

      await(secondOutcome) shouldBe WebviewLoadCoordinator.Outcome.Show(BASE_URI, "Flow Builder", session)
      await(firstOutcome) shouldBe WebviewLoadCoordinator.Outcome.Superseded
    }

    it("reports an advertised uri that cannot carry a query as a failure message") {
      val session = LanguageServerSession()
      val resolution = CompletableFuture.completedFuture(
        WebviewResolution.Resolved(WEBVIEW_ID, "Flow Builder", "mailto:duo@example.com", session) as WebviewResolution,
      )
      val coordinator = WebviewLoadCoordinator(resolverReturning(resolution), wrapperWith(session), runInline)

      await(coordinator.load(WEBVIEW_ID, mapOf("uri" to "file:///a.yml"))) shouldBe
        WebviewLoadCoordinator.Outcome.Message(WebviewLoadCoordinator.MESSAGE_UNREACHABLE)
    }

    it("maps LanguageServerUnavailable to the waiting message") {
      val coordinator = WebviewLoadCoordinator(
        resolverReturning(CompletableFuture.completedFuture(WebviewResolution.LanguageServerUnavailable)),
        wrapperWith(LanguageServerSession()),
        runInline,
      )

      await(coordinator.load(WEBVIEW_ID, emptyMap())) shouldBe
        WebviewLoadCoordinator.Outcome.Message(WebviewLoadCoordinator.MESSAGE_WAITING)
    }

    it("maps Failed to the unreachable message") {
      val coordinator = WebviewLoadCoordinator(
        resolverReturning(CompletableFuture.completedFuture(WebviewResolution.Failed(RuntimeException("boom")))),
        wrapperWith(LanguageServerSession()),
        runInline,
      )

      await(coordinator.load(WEBVIEW_ID, emptyMap())) shouldBe
        WebviewLoadCoordinator.Outcome.Message(WebviewLoadCoordinator.MESSAGE_UNREACHABLE)
    }

    it("maps NotAdvertised to the not-provided message") {
      val coordinator = WebviewLoadCoordinator(
        resolverReturning(CompletableFuture.completedFuture(WebviewResolution.NotAdvertised(WEBVIEW_ID))),
        wrapperWith(LanguageServerSession()),
        runInline,
      )

      await(coordinator.load(WEBVIEW_ID, emptyMap())) shouldBe
        WebviewLoadCoordinator.Outcome.Message(WebviewLoadCoordinator.notProvidedMessage(WEBVIEW_ID))
    }

    it("maps NoUri to the not-provided message") {
      val coordinator = WebviewLoadCoordinator(
        resolverReturning(CompletableFuture.completedFuture(WebviewResolution.NoUri(WEBVIEW_ID))),
        wrapperWith(LanguageServerSession()),
        runInline,
      )

      await(coordinator.load(WEBVIEW_ID, emptyMap())) shouldBe
        WebviewLoadCoordinator.Outcome.Message(WebviewLoadCoordinator.notProvidedMessage(WEBVIEW_ID))
    }

    it("completes with a failure message when the resolver's future completes exceptionally") {
      val broken = CompletableFuture<WebviewResolution>()
      broken.completeExceptionally(IllegalStateException("resolver broke its never-throws contract"))
      val coordinator =
        WebviewLoadCoordinator(resolverReturning(broken), wrapperWith(LanguageServerSession()), runInline)

      await(coordinator.load(WEBVIEW_ID, emptyMap())) shouldBe
        WebviewLoadCoordinator.Outcome.Message(WebviewLoadCoordinator.MESSAGE_UNREACHABLE)
    }

    it("completes with a failure message when deciding itself throws") {
      val session = LanguageServerSession()
      val wrapper = mockk<GitLabLanguageServerWrapper>()
      every { wrapper.currentSnapshot } throws IllegalStateException("snapshot exploded")
      val resolution = CompletableFuture.completedFuture(
        WebviewResolution.Resolved(WEBVIEW_ID, "Flow Builder", BASE_URI, session) as WebviewResolution,
      )
      val coordinator = WebviewLoadCoordinator(resolverReturning(resolution), wrapper, runInline)

      await(coordinator.load(WEBVIEW_ID, emptyMap())) shouldBe
        WebviewLoadCoordinator.Outcome.Message(WebviewLoadCoordinator.MESSAGE_UNREACHABLE)
    }

    it("decides on the injected marshaller rather than on the thread that completed the resolution") {
      val session = LanguageServerSession()
      val queued = mutableListOf<() -> Unit>()
      val coordinator = WebviewLoadCoordinator(
        resolverReturning(
          CompletableFuture.completedFuture(
            WebviewResolution.Resolved(WEBVIEW_ID, "Flow Builder", BASE_URI, session) as WebviewResolution,
          ),
        ),
        wrapperWith(session),
        { queued += it },
      )

      val outcome = coordinator.load(WEBVIEW_ID, emptyMap())

      await(outcome).shouldBeNull()
      queued.forEach { it() }
      (await(outcome) as WebviewLoadCoordinator.Outcome.Show).session shouldBeSameInstanceAs session
    }
  }

  // Ruling R1 / A8 at tab level (plan §12 / §15): the Knowledge Graph is never advertised, and a
  // missing address means `gkg` is not running — the user's environment, not a failure. It gets its
  // own explanation and no Error Log entry; every other id keeps the not-provided page and its entry.
  describe("the Knowledge Graph's not-running page") {
    fun capturingLog(): ILog {
      val log = mockk<ILog>(relaxUnitFun = true)
      every { Platform.getLog(any<Bundle>()) } returns log
      return log
    }

    fun coordinatorResolving(id: String, resolution: WebviewResolution): WebviewLoadCoordinator {
      val resolver = mockk<WebviewUriResolver>()
      every { resolver.resolve(id) } returns CompletableFuture.completedFuture(resolution)
      return WebviewLoadCoordinator(resolver, wrapperWith(LanguageServerSession()), runInline)
    }

    it("explains that the graph is not running, without an Error Log entry") {
      val log = capturingLog()
      val id = KnowledgeGraphState.WEBVIEW_ID
      val coordinator = coordinatorResolving(id, WebviewResolution.NotAdvertised(id))

      await(coordinator.load(id, emptyMap())) shouldBe
        WebviewLoadCoordinator.Outcome.Message(KnowledgeGraphState.NOT_RUNNING_MESSAGE)
      verify(exactly = 0) { log.error(any<String>()) }
      verify(exactly = 0) { log.error(any<String>(), any()) }
      verify(exactly = 0) { log.log(match { it.severity == IStatus.ERROR }) }
    }

    it("keeps the not-provided page and its Error Log entry for every other id") {
      val log = capturingLog()
      val coordinator = coordinatorResolving(WEBVIEW_ID, WebviewResolution.NotAdvertised(WEBVIEW_ID))

      await(coordinator.load(WEBVIEW_ID, emptyMap())) shouldBe
        WebviewLoadCoordinator.Outcome.Message(WebviewLoadCoordinator.notProvidedMessage(WEBVIEW_ID))
      verify(exactly = 1) { log.error("Cannot show webview '$WEBVIEW_ID': not advertised") }
    }

    it("keeps NoUri and Failed for the graph on their existing pages") {
      capturingLog()
      val id = KnowledgeGraphState.WEBVIEW_ID

      await(coordinatorResolving(id, WebviewResolution.NoUri(id)).load(id, emptyMap())) shouldBe
        WebviewLoadCoordinator.Outcome.Message(WebviewLoadCoordinator.notProvidedMessage(id))
      await(coordinatorResolving(id, WebviewResolution.Failed(null)).load(id, emptyMap())) shouldBe
        WebviewLoadCoordinator.Outcome.Message(WebviewLoadCoordinator.MESSAGE_UNREACHABLE)
    }
  }

  // Design §17. Same class of pin as `WebviewEditorInputTest`'s two and `WebviewUriResolverTest`'s.
  // This branch is what put the user's file path into `url`, so `Show` is the newest carrier of it.
  describe("Outcome.Show's string form") {
    it("keeps the queried path out of it") {
      val url = "$BASE_URI?uri=file%3A%2F%2F%2Fhome%2Falice%2Fsecret-project.yml"

      val rendered = "${WebviewLoadCoordinator.Outcome.Show(url, "Flow Builder", LanguageServerSession())}"

      rendered shouldNotContain "secret-project"
      rendered shouldBe "Outcome.Show(title=Flow Builder)"
    }
  }
})
