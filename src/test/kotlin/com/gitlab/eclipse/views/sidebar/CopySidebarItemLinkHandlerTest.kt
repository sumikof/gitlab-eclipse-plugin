package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.navigation.ClipboardTarget
import com.gitlab.eclipse.navigation.ClipboardWriter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.eclipse.swt.SWTError
import org.eclipse.swt.dnd.DND

private const val PIPELINE_URL = "https://gitlab.example.com/g/p/-/pipelines/1"
private const val MR_URL = "https://gitlab.example.com/g/p/-/merge_requests/3"

class CopySidebarItemLinkHandlerTest : StringSpec({
  extensions(LoggingKotestExtension)

  val written = mutableListOf<String>()
  val notices = mutableListOf<String>()

  beforeTest {
    written.clear()
    notices.clear()
  }

  fun handler(failing: Boolean = false): CopySidebarItemLinkHandler {
    val target = object : ClipboardTarget {
      override fun setText(text: String) {
        if (failing) throw SWTError(DND.ERROR_CANNOT_SET_CLIPBOARD, "clipboard busy")
        written += text
      }

      override fun dispose() = Unit
    }
    return CopySidebarItemLinkHandler(
      clipboard = ClipboardWriter(onUiThread = { it.run() }, openClipboard = { target }),
      notify = { notices += it },
    )
  }

  "no selection only notifies" {
    handler().run(null)
    notices shouldContainExactly listOf(SELECT_SIDEBAR_ITEM_MESSAGE)
    written.shouldBeEmpty()
  }

  "a node without a web URL, or with a non-http(s) one, only notifies" {
    handler().run(MessageNode("hi"))
    handler().run(linkPipeline("file:///etc/passwd"))
    notices shouldContainExactly listOf(NO_SIDEBAR_LINK_MESSAGE, NO_SIDEBAR_LINK_MESSAGE)
    written.shouldBeEmpty()
  }

  "a node with a web URL copies it once and says so" {
    handler().run(linkPipeline(PIPELINE_URL))
    written shouldContainExactly listOf(PIPELINE_URL)
    notices shouldContainExactly listOf(ClipboardWriter.COPIED_TO_CLIPBOARD)
  }

  "a merge request copies its own web URL" {
    handler().run(linkMergeRequest(MR_URL))
    written shouldContainExactly listOf(MR_URL)
  }

  "a copy that did not land says nothing" {
    handler(failing = true).run(linkPipeline(PIPELINE_URL))
    written.shouldBeEmpty()
    notices.shouldBeEmpty()
  }
})
