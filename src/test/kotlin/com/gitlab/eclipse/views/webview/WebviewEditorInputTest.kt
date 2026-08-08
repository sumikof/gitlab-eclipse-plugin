package com.gitlab.eclipse.views.webview

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.eclipse.ui.IEditorInput

private const val FLOW_ID = "root/flow"
private const val MCP_ID = "root/mcp"
private const val FLOW_TITLE = "Flow Builder"
private const val SECRET_PATH = "file:///home/alice/secret-project/pipeline.yml"

private fun flowInput(
  path: String = SECRET_PATH,
  title: String = FLOW_TITLE,
) = WebviewEditorInput(WebviewEditorKey(FLOW_ID, mapOf("uri" to path)), title)

class WebviewEditorInputTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("WebviewEditorInput") {
    it("is equal to another input built from the same key") {
      flowInput() shouldBe flowInput()
    }

    it("hashes the same as another input built from the same key") {
      flowInput().hashCode() shouldBe flowInput().hashCode()
    }

    // Design §21 A14.
    it("differs from an input whose queryParams differ") {
      flowInput() shouldNotBe flowInput(path = "file:///home/alice/other.yml")
    }

    it("differs from an input whose webviewId differs") {
      flowInput() shouldNotBe WebviewEditorInput(
        WebviewEditorKey(MCP_ID, mapOf("uri" to SECRET_PATH)),
        FLOW_TITLE,
      )
    }

    it("ignores fallbackTitle when comparing") {
      flowInput() shouldBe flowInput(title = "Something Else")
    }

    it("ignores fallbackTitle when hashing") {
      flowInput().hashCode() shouldBe flowInput(title = "Something Else").hashCode()
    }

    it("is not equal to an unrelated editor input") {
      val other = object : IEditorInput by flowInput() {}
      flowInput() shouldNotBe other
    }

    // Design §21 A7.
    it("does not exist") {
      flowInput().exists() shouldBe false
    }

    // Design §21 A7.
    it("has no persistable element") {
      flowInput().persistable.shouldBeNull()
    }

    it("adapts to nothing") {
      flowInput().getAdapter(String::class.java).shouldBeNull()
    }

    it("has no image descriptor") {
      flowInput().imageDescriptor.shouldBeNull()
    }

    it("names the tab with the fallback title") {
      flowInput().name shouldBe FLOW_TITLE
    }

    // Design §17: the file path in queryParams must not reach anything shown or logged.
    it("keeps the queried path out of the tooltip") {
      flowInput().toolTipText shouldNotContain "secret-project"
    }
  }

  describe("WebviewEditorKey") {
    // Design §17: a data class prints every component, and this one holds the user's file path.
    it("keeps the queried path out of its own string form") {
      val rendered = "${WebviewEditorKey(FLOW_ID, mapOf("uri" to SECRET_PATH))}"

      rendered shouldNotContain "secret-project"
      rendered shouldBe "WebviewEditorKey($FLOW_ID)"
    }
  }

  describe("ActiveYamlEditorUri.Resolved") {
    // Design §17: the same channel as the key's, on the type that carries the path to it.
    it("keeps the resolved file out of its own string form") {
      val rendered = "${ActiveYamlEditorUri.Resolved(SECRET_PATH)}"

      rendered shouldNotContain "secret-project"
      rendered shouldBe "ActiveYamlEditorUri.Resolved"
    }
  }

  describe("WebviewEditorInput factories") {
    it("names the MCP dashboard with the title the language server advertises") {
      WebviewEditorInput.mcp().name shouldBe "MCP Dashboard"
    }

    it("keys the MCP dashboard by its id and no query, so there is one tab for it") {
      WebviewEditorInput.mcp().key shouldBe WebviewEditorKey(MCP_ID, emptyMap())
    }

    it("names the flow builder with the title the language server advertises") {
      WebviewEditorInput.flowBuilder(SECRET_PATH).name shouldBe FLOW_TITLE
    }

    it("keys the flow builder by the file it was opened for") {
      WebviewEditorInput.flowBuilder(SECRET_PATH).key shouldBe
        WebviewEditorKey(FLOW_ID, mapOf("uri" to SECRET_PATH))
    }
  }
})
