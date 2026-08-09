package com.gitlab.eclipse.lsp.webview

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.LanguageServerSession
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class WebviewEditorTitlesTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("titleFor") {
    it("returns the advertised title of a resolved webview") {
      val resolution = WebviewResolution.Resolved(
        id = "root/flow",
        title = "Flow Builder",
        uri = "https://gitlab.example.com/root/flow",
        session = LanguageServerSession(),
      )

      WebviewEditorTitles.titleFor(resolution) shouldBe "Flow Builder"
    }

    it("returns null for every resolution that is not a success") {
      val unresolved = listOf(
        WebviewResolution.LanguageServerUnavailable,
        WebviewResolution.Failed(RuntimeException("boom")),
        WebviewResolution.NotAdvertised("root/flow"),
        WebviewResolution.NoUri("root/flow"),
      )

      unresolved.forEach { WebviewEditorTitles.titleFor(it).shouldBeNull() }
    }
  }
})
