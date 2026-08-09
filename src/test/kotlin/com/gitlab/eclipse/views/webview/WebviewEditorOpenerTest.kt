package com.gitlab.eclipse.views.webview

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerHandle
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.lsp.webview.WebviewLoadCoordinator
import com.gitlab.eclipse.lsp.webview.WebviewLoadPipeline
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.ui.IEditorPart
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.IWorkbenchPage
import java.util.concurrent.CompletableFuture

private const val WEBVIEW_ID = "root/flow"
private const val RESOLVED_URL = "https://gitlab.example.com/root/flow?uri=file%3A%2F%2F%2Fp.yml"
private const val RESOLVED_TITLE = "Flow Builder"

/**
 * The tab a surface stands for, driven through a real [WebviewLoadPipeline] the way
 * [WebviewEditorPart] drives one, so that what design §21's A22 checks is a load that reaches the
 * pipeline rather than a recorded call to [WebviewEditorSurface.reload].
 */
private class Fixture(primed: Boolean = true) {
  val openedSession = LanguageServerSession()
  val loads = mutableListOf<String>()

  val coordinator = mockk<WebviewLoadCoordinator>()
  val wrapper = mockk<GitLabLanguageServerWrapper>()
  val page = mockk<IWorkbenchPage>(relaxUnitFun = true)
  val input = WebviewEditorInput.flowBuilder("file:///p.yml")

  private val pipeline = WebviewLoadPipeline(
    coordinator = coordinator,
    showUrl = {},
    showMessage = {},
    setTitle = {},
    hasStableContent = { false },
    setLoadingVisible = {},
    isAlive = { true },
  )

  val surface = mockk<WebviewEditorSurface>()

  init {
    every { coordinator.load(any(), any()) } answers {
      loads += firstArg<String>()
      CompletableFuture.completedFuture(
        WebviewLoadCoordinator.Outcome.Show(RESOLVED_URL, RESOLVED_TITLE, openedSession),
      )
    }
    every { surface.displayedSession } answers { pipeline.displayedSession }
    every { surface.reload() } answers { pipeline.load(WEBVIEW_ID, input.key.queryParams) }
    every { page.openEditor(any(), any()) } returns mockk<IEditorPart>()

    // The tab as the user left it: with [primed], one load that succeeded, so the pipeline recorded
    // the session that URL came from (design §7.2b); without it, a tab that has applied nothing.
    if (primed) {
      surface.reload()
      loads shouldContainExactly listOf(WEBVIEW_ID)
      pipeline.displayedSession shouldBeSameInstanceAs openedSession
      loads.clear()
    } else {
      pipeline.displayedSession.shouldBeNull()
    }
  }

  /** [page] holds the tab [surface] stands for. */
  fun pageHoldsTheTab() = matches(referenceTo(surface))

  fun matches(vararg references: IEditorReference) {
    every { page.findEditors(input, null, IWorkbenchPage.MATCH_INPUT) } returns arrayOf(*references)
  }

  fun referenceTo(part: IEditorPart?): IEditorReference =
    mockk<IEditorReference>().also { every { it.getEditor(any()) } returns part }

  /** The session the language server connection currently in place belongs to. */
  fun currentSessionIs(session: LanguageServerSession?) {
    every { wrapper.currentSnapshot } returns
      session?.let { LanguageServerHandle(mockk<GitLabLanguageServer>(), it) }
  }

  fun openOrReload() = WebviewEditorOpener(wrapper).openOrReload(page, input)
}

class WebviewEditorOpenerTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("WebviewEditorOpener.openOrReload") {
    it("opens a tab when the active page has none for the input") {
      val fixture = Fixture()
      fixture.matches()
      fixture.currentSessionIs(fixture.openedSession)

      fixture.openOrReload()

      verify(exactly = 1) { fixture.page.openEditor(fixture.input, WebviewEditorPart.EDITOR_ID) }
    }

    it("activates the tab that is already open") {
      val fixture = Fixture()
      fixture.pageHoldsTheTab()
      fixture.currentSessionIs(fixture.openedSession)

      fixture.openOrReload()

      verify(exactly = 1) { fixture.page.activate(fixture.surface) }
    }

    // Design §21 A22.
    it("loads the open tab again when what it shows is from another session") {
      val fixture = Fixture()
      fixture.pageHoldsTheTab()
      fixture.currentSessionIs(LanguageServerSession())

      fixture.openOrReload()

      fixture.loads shouldContainExactly listOf(WEBVIEW_ID)
    }

    // Design §21 A22.
    it("does not load the open tab again when what it shows is from the current session") {
      val fixture = Fixture()
      fixture.pageHoldsTheTab()
      fixture.currentSessionIs(fixture.openedSession)

      fixture.openOrReload()

      fixture.loads.shouldBeEmpty()
    }

    // Design §7.2b: a resolution that produced no content leaves nothing recorded, and the command
    // run after it has to resolve again.
    it("loads the open tab again when it has shown nothing and there is no language server") {
      val fixture = Fixture(primed = false)
      fixture.pageHoldsTheTab()
      fixture.currentSessionIs(null)

      fixture.openOrReload()

      fixture.loads shouldContainExactly listOf(WEBVIEW_ID)
    }

    it("does not take a matching reference that yields no webview surface for an open tab") {
      val fixture = Fixture()
      fixture.matches(fixture.referenceTo(null), fixture.referenceTo(mockk<IEditorPart>()))
      fixture.currentSessionIs(fixture.openedSession)

      fixture.openOrReload()

      verify(exactly = 0) { fixture.page.activate(any()) }
      fixture.loads.shouldBeEmpty()
    }
  }
})
