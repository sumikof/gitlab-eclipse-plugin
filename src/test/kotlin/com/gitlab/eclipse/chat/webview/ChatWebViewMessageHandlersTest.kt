package com.gitlab.eclipse.chat.webview

import com.gitlab.eclipse.chat.context.CurrentFileContextProvider
import com.gitlab.eclipse.chat.services.InsertCodeSnippetService
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.navigation.BrowserLauncher
import com.gitlab.eclipse.navigation.ClipboardTarget
import com.gitlab.eclipse.navigation.ClipboardWriter
import com.gitlab.eclipse.utils.PlatformUtils
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.swt.SWTError
import org.eclipse.swt.dnd.DND

/**
 * The webview copy path (the clipboard write, and the notice that must follow only a real write) and the link path
 * (which link reaches the BrowserLauncher gate, and through which entry).
 */
class ChatWebViewMessageHandlersTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val written = mutableListOf<String>()
  val notices = mutableListOf<String>()
  val deferred = mutableListOf<Runnable>()

  beforeTest {
    written.clear()
    notices.clear()
    deferred.clear()
  }

  /** A clipboard that records, or one that refuses every write with SWT's own error. */
  fun clipboard(failing: Boolean = false) = object : ClipboardTarget {
    override fun setText(text: String) {
      if (failing) throw SWTError(DND.ERROR_CANNOT_SET_CLIPBOARD, "clipboard busy")
      written += text
    }

    override fun dispose() = Unit
  }

  fun handlers(
    failing: Boolean = false,
    onUiThread: (Runnable) -> Unit = { it.run() },
    browser: BrowserLauncher = mockk(relaxUnitFun = true),
  ) = ChatWebViewMessageHandlers(
    platformUtils = mockk<PlatformUtils>(),
    currentFileContextProvider = mockk<CurrentFileContextProvider>(),
    insertCodeSnippetService = mockk<InsertCodeSnippetService>(),
    clipboardWriter = ClipboardWriter(onUiThread = onUiThread, openClipboard = { clipboard(failing) }),
    notify = { notices += it },
    browser = browser,
  )

  describe("openLink") {
    // The webview notification arrives on a ForkJoinPool thread, so the link must go through `open` (which hops to
    // the UI thread itself) and never `openChecked` (UI thread only).
    it("hands the classic chat's href to the gate's any-thread entry exactly once") {
      val browser = mockk<BrowserLauncher>(relaxUnitFun = true)

      handlers(browser = browser)
        .openLink(OpenLinkNotification(href = "https://gitlab.com/a", url = "https://gitlab.com/b"))

      verify(exactly = 1) { browser.open("https://gitlab.com/a") }
      verify(exactly = 1) { browser.open(any()) }
      verify(exactly = 0) { browser.openChecked(any()) }
    }

    it("falls back to the agentic chat's url when there is no href") {
      val browser = mockk<BrowserLauncher>(relaxUnitFun = true)

      handlers(browser = browser).openLink(OpenLinkNotification(href = null, url = "https://gitlab.com/b"))

      verify(exactly = 1) { browser.open("https://gitlab.com/b") }
      verify(exactly = 1) { browser.open(any()) }
      verify(exactly = 0) { browser.openChecked(any()) }
    }

    it("opens nothing when the notification carries neither") {
      val browser = mockk<BrowserLauncher>(relaxUnitFun = true)

      handlers(browser = browser).openLink(OpenLinkNotification(href = null, url = null))

      verify(exactly = 0) { browser.open(any()) }
      verify(exactly = 0) { browser.openChecked(any()) }
    }
  }

  describe("copyCodeSnippet") {
    it("puts the snippet on the clipboard and then says so") {
      handlers().copyCodeSnippet(CopyCodeSnippetNotification("println(1)"))

      written.shouldContainExactly("println(1)")
      notices.shouldContainExactly(ClipboardWriter.COPIED_TO_CLIPBOARD)
    }

    it("says nothing when the clipboard refused the snippet") {
      handlers(failing = true).copyCodeSnippet(CopyCodeSnippetNotification("println(1)"))

      written.shouldBeEmpty()
      notices.shouldBeEmpty()
    }
  }

  describe("copyMessage") {
    it("puts the whole message on the clipboard and then says so") {
      handlers().copyMessage(CopyMessageNotification("the full assistant message"))

      written.shouldContainExactly("the full assistant message")
      notices.shouldContainExactly(ClipboardWriter.COPIED_TO_CLIPBOARD)
    }

    it("says nothing when the clipboard refused the message") {
      handlers(failing = true).copyMessage(CopyMessageNotification("the full assistant message"))

      written.shouldBeEmpty()
      notices.shouldBeEmpty()
    }

    it("returns without waiting for the UI turn, and says so only once that turn has written") {
      handlers(onUiThread = { deferred += it }).copyMessage(CopyMessageNotification("later"))

      written.shouldBeEmpty()
      notices.shouldBeEmpty()
      deferred.single().run()
      written.shouldContainExactly("later")
      notices.shouldContainExactly(ClipboardWriter.COPIED_TO_CLIPBOARD)
    }
  }
})
