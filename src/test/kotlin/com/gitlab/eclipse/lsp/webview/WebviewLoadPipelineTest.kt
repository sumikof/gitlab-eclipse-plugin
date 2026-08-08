package com.gitlab.eclipse.lsp.webview

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerHandle
import com.gitlab.eclipse.lsp.LanguageServerSession
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle
import java.util.concurrent.CompletableFuture

private const val WEBVIEW_ID = "root/flow"
private const val BASE_URI = "https://gitlab.example.com/root/flow"
private const val TITLE = "Flow Builder"

private const val SHOW_URL = "showUrl"
private const val SHOW_MESSAGE = "showMessage"
private const val SET_TITLE = "setTitle"
private const val LOADING = "loading"
private const val STABLE_PROBE = "hasStableContent"

/**
 * The sinks design §7.2 says the SWT shell supplies, recorded in call order so that a test can
 * assert both what a sink received and that a sink was never reached.
 */
private class Sinks {
  val calls = mutableListOf<String>()
  var stableContent = false
  var alive = true
  var stableContentFailure: Throwable? = null
  var showUrlFailure: Throwable? = null
  var showMessageFailure: Throwable? = null
  var setTitleFailure: Throwable? = null
  var setLoadingFailure: Throwable? = null

  fun showUrl(url: String) {
    calls += "$SHOW_URL($url)"
    showUrlFailure?.let { throw it }
  }

  fun showMessage(text: String) {
    calls += "$SHOW_MESSAGE($text)"
    showMessageFailure?.let { throw it }
  }

  fun setTitle(title: String) {
    calls += "$SET_TITLE($title)"
    setTitleFailure?.let { throw it }
  }

  fun setLoadingVisible(visible: Boolean) {
    calls += "$LOADING($visible)"
    setLoadingFailure?.let { throw it }
  }

  fun hasStableContent(): Boolean {
    calls += STABLE_PROBE
    stableContentFailure?.let { throw it }
    return stableContent
  }

  fun isAlive(): Boolean = alive

  /** The arguments every call of one sink received, in order. */
  fun argsOf(sink: String): List<String> =
    calls.filter { it.startsWith("$sink(") }.map { it.substringAfter('(').dropLast(1) }
}

private class Fixture(coordinatorOverride: WebviewLoadCoordinator? = null) {
  val session = LanguageServerSession()
  val wrapper = mockk<GitLabLanguageServerWrapper>()
  val resolver = mockk<WebviewUriResolver>()
  val sinks = Sinks()
  val coordinator = coordinatorOverride ?: WebviewLoadCoordinator(resolver, wrapper) { it() }
  val pipeline = WebviewLoadPipeline(
    coordinator,
    showUrl = sinks::showUrl,
    showMessage = sinks::showMessage,
    setTitle = sinks::setTitle,
    hasStableContent = sinks::hasStableContent,
    setLoadingVisible = sinks::setLoadingVisible,
    isAlive = sinks::isAlive,
  )

  init {
    every { wrapper.currentSnapshot } returns LanguageServerHandle(mockk<GitLabLanguageServer>(), session)
  }

  fun resolvesWith(vararg futures: CompletableFuture<WebviewResolution>) {
    every { resolver.resolve(any()) } returnsMany futures.toList()
  }

  fun resolvedTo(uri: String = BASE_URI, session: LanguageServerSession = this.session) =
    CompletableFuture.completedFuture(
      WebviewResolution.Resolved(WEBVIEW_ID, TITLE, uri, session) as WebviewResolution,
    )
}

/** A coordinator that hands the pipeline one chosen outcome, for outcomes no resolution produces. */
private fun coordinatorYielding(outcome: CompletableFuture<WebviewLoadCoordinator.Outcome>): WebviewLoadCoordinator {
  val coordinator = mockk<WebviewLoadCoordinator>()
  every { coordinator.load(any(), any()) } returns outcome
  return coordinator
}

/**
 * Installs a log that records every string it is handed, and returns that recording. With
 * [failing], it instead throws the way the platform log does once the workbench is stopping.
 */
private fun captureLog(failing: Boolean = false): List<String> {
  val recorded = mutableListOf<String>()
  val log = mockk<ILog>()
  val message = slot<String>()
  val status = slot<IStatus>()
  val record: (String) -> Unit = {
    if (failing) error("the platform log is gone")
    recorded += it
  }
  every { log.error(capture(message)) } answers { record(message.captured) }
  every { log.error(capture(message), any()) } answers { record(message.captured) }
  every { log.warn(capture(message)) } answers { record(message.captured) }
  every { log.warn(capture(message), any()) } answers { record(message.captured) }
  every { log.info(capture(message)) } answers { record(message.captured) }
  every { log.info(capture(message), any()) } answers { record(message.captured) }
  every { log.log(capture(status)) } answers { record(status.captured.message) }
  every { Platform.getLog(any<Bundle>()) } returns log
  every { Platform.getLog(any<Class<*>>()) } returns log
  return recorded
}

class WebviewLoadPipelineTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("load") {
    // A15 (design §21): inspects the argument showUrl actually received, from the production entry point.
    it("hands showUrl the advertised uri with the query parameters appended and percent-encoded") {
      val fixture = Fixture()
      fixture.resolvesWith(fixture.resolvedTo("$BASE_URI?a=%26b#frag"))

      fixture.pipeline.load(WEBVIEW_ID, mapOf("uri" to "file:///w/a&b#c=d?e f+g%h日本語i.yml"))

      fixture.sinks.argsOf(SHOW_URL) shouldContainExactly listOf(
        "$BASE_URI?a=%26b&uri=file%3A%2F%2F%2Fw%2Fa%26b%23c%3Dd%3Fe%20f%2Bg%25h%E6%97%A5%E6%9C%AC%E8%AA%9Ei.yml#frag",
      )
    }

    // A16 (design §21): the generation is left untouched — only the session differs.
    it("never reaches showUrl or setTitle for a resolution that belongs to a previous session") {
      val fixture = Fixture()
      fixture.resolvesWith(fixture.resolvedTo(session = LanguageServerSession()))

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.sinks.argsOf(SHOW_URL).shouldBeEmpty()
      fixture.sinks.argsOf(SET_TITLE).shouldBeEmpty()
    }

    // A13 (design §21).
    it("never reaches setTitle when the webview cannot be resolved") {
      val fixture = Fixture()
      fixture.resolvesWith(CompletableFuture.completedFuture(WebviewResolution.NotAdvertised(WEBVIEW_ID)))

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.sinks.argsOf(SHOW_MESSAGE).shouldNotBeEmpty() // precondition: the failure really was applied
      fixture.sinks.argsOf(SET_TITLE).shouldBeEmpty()
    }

    // A33 (design §21).
    it("touches no sink for a load that a newer load overtook and finished before it") {
      val fixture = Fixture()
      val first = CompletableFuture<WebviewResolution>()
      val second = CompletableFuture<WebviewResolution>()
      fixture.resolvesWith(first, second)
      val resolved = WebviewResolution.Resolved(WEBVIEW_ID, TITLE, BASE_URI, fixture.session)

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())
      fixture.pipeline.load(WEBVIEW_ID, emptyMap())
      second.complete(resolved)
      val afterNewerLoadSucceeded = fixture.sinks.calls.toList()
      first.complete(resolved)

      fixture.sinks.calls shouldContainExactly afterNewerLoadSucceeded
    }

    // A29 (design §21).
    it("shows the message page when the session changes and nothing stable is on screen") {
      val fixture = Fixture()
      fixture.sinks.stableContent = false
      fixture.resolvesWith(fixture.resolvedTo(session = LanguageServerSession()))

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.sinks.argsOf(SHOW_MESSAGE) shouldContainExactly listOf(WebviewLoadPipeline.MESSAGE_SESSION_CHANGED)
    }

    // A29 (design §21).
    it("leaves a stable display alone when the session changes") {
      val fixture = Fixture()
      fixture.sinks.stableContent = true
      fixture.resolvesWith(fixture.resolvedTo(session = LanguageServerSession()))

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.sinks.argsOf(SHOW_MESSAGE).shouldBeEmpty()
      fixture.sinks.argsOf(LOADING).shouldContainExactly(listOf("false"))
    }

    // A29 (design §21).
    it("hides the loading page again after a session change") {
      val fixture = Fixture()
      fixture.sinks.stableContent = false
      fixture.resolvesWith(fixture.resolvedTo(session = LanguageServerSession()))

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.sinks.argsOf(LOADING) shouldContainExactly listOf("true", "false")
    }

    // A34 (design §21).
    it("hides the loading page even when probing for stable content and showing the message both throw") {
      val fixture = Fixture()
      fixture.sinks.stableContentFailure = IllegalStateException("the stack layout is gone")
      fixture.sinks.showMessageFailure = IllegalStateException("the message browser is gone")
      fixture.resolvesWith(fixture.resolvedTo(session = LanguageServerSession()))

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.sinks.argsOf(LOADING) shouldContainExactly listOf("true", "false")
    }

    // A34 (design §21): the platform log can be gone while the workbench is stopping, and the log
    // writes inside `settle`'s `try` block are deliberately left able to throw, so that is what
    // still escapes applying an outcome and reaches the `finally`.
    it("hides the loading page even when the log that records a sink failure throws") {
      captureLog(failing = true)
      val fixture = Fixture()
      fixture.sinks.showMessageFailure = IllegalStateException("the message browser is gone")
      fixture.resolvesWith(fixture.resolvedTo(session = LanguageServerSession()))

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.sinks.argsOf(LOADING) shouldContainExactly listOf("true", "false")
    }

    it("treats a stable-content probe that throws as nothing stable being on screen") {
      val fixture = Fixture()
      fixture.sinks.stableContent = true
      fixture.sinks.stableContentFailure = IllegalStateException("the stack layout is gone")
      fixture.resolvesWith(fixture.resolvedTo())

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.sinks.argsOf(LOADING).first() shouldBe "true"
    }

    // A31 (design §21).
    it("drives the url and the tab name, and not the message page, for a resolved webview") {
      val fixture = Fixture()
      fixture.resolvesWith(fixture.resolvedTo())

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.sinks.argsOf(SHOW_URL) shouldContainExactly listOf(BASE_URI)
      fixture.sinks.argsOf(SET_TITLE) shouldContainExactly listOf(TITLE)
      fixture.sinks.argsOf(SHOW_MESSAGE).shouldBeEmpty()
    }

    // A31 (design §21).
    it("drives the message page, and not the browser, for a failure") {
      val fixture = Fixture()
      fixture.resolvesWith(CompletableFuture.completedFuture(WebviewResolution.LanguageServerUnavailable))

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.sinks.argsOf(SHOW_MESSAGE) shouldContainExactly listOf(WebviewLoadCoordinator.MESSAGE_WAITING)
      fixture.sinks.argsOf(SHOW_URL).shouldBeEmpty()
    }

    it("leaves the tab name alone when the outcome carries no title") {
      val outcome = CompletableFuture.completedFuture(
        WebviewLoadCoordinator.Outcome.Show(BASE_URI, null, LanguageServerSession())
          as WebviewLoadCoordinator.Outcome,
      )
      val fixture = Fixture(coordinatorYielding(outcome))

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.sinks.argsOf(SHOW_URL) shouldContainExactly listOf(BASE_URI)
      fixture.sinks.argsOf(SET_TITLE).shouldBeEmpty()
    }

    it("hides the loading page even when the coordinator's future completes exceptionally") {
      val broken = CompletableFuture<WebviewLoadCoordinator.Outcome>()
      broken.completeExceptionally(IllegalStateException("the coordinator broke its contract"))
      val fixture = Fixture(coordinatorYielding(broken))

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.sinks.argsOf(LOADING) shouldContainExactly listOf("true", "false")
    }

    // The A34 (design §21) dead-log case one layer down: the coordinator has no `finally`, so a
    // dead platform log there would leave its outcome uncompleted and this pipeline's loading page
    // up for good.
    it("hides the loading page even when the log that records a resolution failure throws") {
      captureLog(failing = true)
      val fixture = Fixture()
      fixture.resolvesWith(CompletableFuture.completedFuture(WebviewResolution.NotAdvertised(WEBVIEW_ID)))

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.sinks.argsOf(LOADING) shouldContainExactly listOf("true", "false")
    }

    // The same, for one of the two log writes `beginLoad` can reach. Neither is covered by
    // `settle`'s `finally`: `beginLoad` runs before that `try`/`finally` is entered at all.
    it("starts the load even when the log that records a failed stable-content probe throws") {
      captureLog(failing = true)
      val fixture = Fixture()
      fixture.sinks.stableContentFailure = IllegalStateException("the stack layout is gone")
      fixture.resolvesWith(fixture.resolvedTo())

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.sinks.argsOf(SHOW_URL) shouldContainExactly listOf(BASE_URI)
    }

    // The other one, and the harder of the two to file correctly: `setLoading` is reached from
    // `beginLoad` and from `settle`'s own `finally`, and a `finally` does not contain a throw
    // raised inside itself. This also covers design §7.2b's setLoadingVisible row — the plain
    // working-log version of it was a strict subset of this setup, so no mutation could fail it
    // without failing this one too.
    it("starts the load even when both the loading toggle and the log that records it throw") {
      captureLog(failing = true)
      val fixture = Fixture()
      fixture.sinks.setLoadingFailure = IllegalStateException("the stack layout is gone")
      fixture.resolvesWith(fixture.resolvedTo())

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.sinks.argsOf(SHOW_URL) shouldContainExactly listOf(BASE_URI)
    }

    // A32 (design §21): the shell is already gone when the load starts and `dispose` is never
    // called, so neither the generation nor the entry refusal applies — only mechanism 2 is left.
    it("touches no sink for a load started after the shell is gone") {
      val fixture = Fixture()
      fixture.sinks.alive = false
      fixture.resolvesWith(fixture.resolvedTo())

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.sinks.calls.shouldBeEmpty()
    }

    // Design §7.2c: advancing the generation only expires the applications already in flight, so a
    // load that starts after dispose needs its own refusal at the entry.
    it("touches no sink for a load called after dispose") {
      val fixture = Fixture()
      fixture.resolvesWith(fixture.resolvedTo())

      fixture.pipeline.dispose()
      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.sinks.calls.shouldBeEmpty()
    }

    it("asks the language server for nothing on a load called after dispose") {
      val fixture = Fixture()
      fixture.resolvesWith(fixture.resolvedTo())

      fixture.pipeline.dispose()
      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      verify(exactly = 0) { fixture.resolver.resolve(any()) }
    }

    // A32 (design §21), mechanism 1 of design §7.2c: the shell is still alive here, so only the
    // generation dispose() advances can stop the in-flight application.
    it("touches no sink once dispose has been called, while the shell is still alive") {
      val fixture = Fixture()
      fixture.sinks.stableContent = true
      val pending = CompletableFuture<WebviewResolution>()
      fixture.resolvesWith(pending)

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())
      fixture.pipeline.dispose()
      val afterDispose = fixture.sinks.calls.toList()
      pending.complete(WebviewResolution.Resolved(WEBVIEW_ID, TITLE, BASE_URI, fixture.session))

      fixture.sinks.alive shouldBe true // precondition: mechanism 2 is not what stopped this
      fixture.sinks.calls shouldContainExactly afterDispose
    }

    // A32 (design §21), mechanism 2 of design §7.2c: dispose() is never called here, so only the
    // isAlive predicate can stop the in-flight application.
    it("touches no sink once the shell is gone, even without a dispose call") {
      val fixture = Fixture()
      fixture.sinks.stableContent = true
      val pending = CompletableFuture<WebviewResolution>()
      fixture.resolvesWith(pending)

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())
      fixture.sinks.alive = false
      val afterShellDied = fixture.sinks.calls.toList()
      pending.complete(WebviewResolution.Resolved(WEBVIEW_ID, TITLE, BASE_URI, fixture.session))

      fixture.sinks.calls shouldContainExactly afterShellDied
    }
  }

  describe("displayedSession") {
    // A30 (design §21).
    it("records the session of a url that was shown") {
      val fixture = Fixture()
      fixture.resolvesWith(fixture.resolvedTo())

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.pipeline.displayedSession shouldBeSameInstanceAs fixture.session
    }

    // A30 (design §21), design §7.2b's showUrl row.
    it("records nothing when showUrl throws") {
      val fixture = Fixture()
      fixture.sinks.showUrlFailure = IllegalStateException("the browser is gone")
      fixture.resolvesWith(fixture.resolvedTo())

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.pipeline.displayedSession.shouldBeNull()
    }

    // A30 (design §21), design §7.2b's setTitle row.
    it("still records the session when only setTitle throws") {
      val fixture = Fixture()
      fixture.sinks.setTitleFailure = IllegalStateException("the part is gone")
      fixture.resolvesWith(fixture.resolvedTo())

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.pipeline.displayedSession shouldBeSameInstanceAs fixture.session
    }

    // A30 (design §21): a transient failure must not make the next command settle for an activate.
    it("records nothing when a message page was shown instead of a webview") {
      val fixture = Fixture()
      fixture.resolvesWith(CompletableFuture.completedFuture(WebviewResolution.Failed(RuntimeException("boom"))))

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.pipeline.displayedSession.shouldBeNull()
    }

    // A30 (design §21), design §7.2b's showMessage row.
    it("records nothing when showMessage throws") {
      val fixture = Fixture()
      fixture.sinks.showMessageFailure = IllegalStateException("the message browser is gone")
      fixture.resolvesWith(CompletableFuture.completedFuture(WebviewResolution.Failed(RuntimeException("boom"))))

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      fixture.pipeline.displayedSession.shouldBeNull()
    }
  }

  describe("logging") {
    // A11 (design §21) and design §17.
    it("keeps the advertised uri and the user's file path out of a resolution-failure log entry") {
      val recorded = captureLog()
      val fixture = Fixture()
      fixture.resolvesWith(fixture.resolvedTo("mailto:duo-secret-token@example.com"))

      fixture.pipeline.load(WEBVIEW_ID, mapOf("uri" to "file:///home/me/secret-path.yml"))

      // That design §12's Error Log entry is written at all is pinned by the two tests below; the
      // branch this one needs is the only resolution failure that has the advertised uri in scope,
      // and asserting on its presence here would make the A15 mutations fail two tests instead of one.
      recorded.forEach {
        it shouldNotContain "duo-secret-token"
        it shouldNotContain "secret-path"
      }
    }

    // A11 (design §21) and design §17.
    it("keeps the resolved url out of a sink-failure log entry") {
      val recorded = captureLog()
      val fixture = Fixture()
      fixture.sinks.showUrlFailure = IllegalStateException("the browser is gone")
      fixture.resolvesWith(fixture.resolvedTo("$BASE_URI/duo-secret-token"))

      fixture.pipeline.load(WEBVIEW_ID, mapOf("uri" to "file:///home/me/secret-path.yml"))

      recorded.shouldNotBeEmpty() // precondition: the sink failure really was logged
      recorded.forEach {
        it shouldNotContain "duo-secret-token"
        it shouldNotContain "secret-path"
      }
    }

    // A11 (design §21) and design §17: an exception contributes its type, never its message.
    it("keeps an exception's message out of the log") {
      val recorded = captureLog()
      val fixture = Fixture()
      fixture.resolvesWith(
        CompletableFuture.completedFuture(
          WebviewResolution.Failed(RuntimeException("failed to open gitlab://webview/duo-secret-token")),
        ),
      )

      fixture.pipeline.load(WEBVIEW_ID, emptyMap())

      recorded.shouldNotBeEmpty() // precondition: design §12's Error Log entry really was written
      recorded.forEach { it shouldNotContain "duo-secret-token" }
      recorded.any { it.contains(RuntimeException::class.java.name) } shouldBe true
    }
  }
})
