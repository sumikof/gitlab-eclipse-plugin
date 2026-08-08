package com.gitlab.eclipse.views.webview

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.eclipse.core.resources.IFile
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IFileEditorInput
import org.eclipse.ui.IURIEditorInput
import java.net.URI

private const val YAML_NAME = "pipeline.yml"
private const val WORKSPACE_URI = "file:///home/alice/proj/pipeline.yml"
private const val EXTERNAL_URI = "file:///tmp/external.yml"

private fun fileInput(name: String = YAML_NAME, locationUri: URI? = URI(WORKSPACE_URI)): IFileEditorInput {
  val file = mockk<IFile>()
  every { file.locationURI } returns locationUri
  val input = mockk<IFileEditorInput>()
  every { input.file } returns file
  every { input.name } returns name
  return input
}

/** An input that offers both of design §7.3a rule 8's sources, which `FileEditorInput` does. */
private fun bothInput(locationUri: URI?): IFileEditorInput {
  val file = mockk<IFile>()
  every { file.locationURI } returns locationUri
  val input = mockk<IFileEditorInput>(moreInterfaces = arrayOf(IURIEditorInput::class))
  every { input.file } returns file
  every { input.name } returns YAML_NAME
  every { (input as IURIEditorInput).uri } returns URI(EXTERNAL_URI)
  return input
}

/** The vehicle for everything that is not about which of design §7.3a rule 8's two sources wins. */
private fun uriInput(name: String = YAML_NAME, uri: URI = URI(EXTERNAL_URI)): IURIEditorInput {
  val input = mockk<IURIEditorInput>()
  every { input.uri } returns uri
  every { input.name } returns name
  return input
}

class ActiveYamlEditorUriTest : DescribeSpec({
  describe("ActiveYamlEditorUri.of") {
    // Design §7.3a rule 8, first source.
    it("takes the location of the workspace file the active editor is on") {
      ActiveYamlEditorUri.of(fileInput()) shouldBe ActiveYamlEditorUri.Resolved(WORKSPACE_URI)
    }

    // Design §7.3a rule 8, second source.
    it("takes the input's own uri when the active editor is not on a workspace file") {
      ActiveYamlEditorUri.of(uriInput()) shouldBe ActiveYamlEditorUri.Resolved(EXTERNAL_URI)
    }

    // Design §7.3a rule 8 puts the two sources in this order, and one input can offer both.
    it("prefers the workspace file's location over the input's own uri") {
      ActiveYamlEditorUri.of(bothInput(URI(WORKSPACE_URI))) shouldBe ActiveYamlEditorUri.Resolved(WORKSPACE_URI)
    }

    it("falls back to the input's own uri when the workspace file has no location") {
      ActiveYamlEditorUri.of(bothInput(null)) shouldBe ActiveYamlEditorUri.Resolved(EXTERNAL_URI)
    }

    // Design §12: a precondition violation, never a silent no-op.
    it("rejects a page with no active editor") {
      ActiveYamlEditorUri.of(null) shouldBe ActiveYamlEditorUri.Rejected.NO_ACTIVE_EDITOR
    }

    // Design §7.3's precondition, after `open_flow_builder.ts:5-8`.
    it("rejects an active editor that is not on a YAML file") {
      ActiveYamlEditorUri.of(fileInput(name = "notes.txt")) shouldBe ActiveYamlEditorUri.Rejected.NOT_YAML
    }

    it("rejects a file whose name carries no extension at all") {
      ActiveYamlEditorUri.of(uriInput(name = "yml")) shouldBe ActiveYamlEditorUri.Rejected.NOT_YAML
    }

    it("accepts the .yaml spelling as well as .yml") {
      ActiveYamlEditorUri.of(uriInput(name = "flow.yaml")) shouldBe ActiveYamlEditorUri.Resolved(EXTERNAL_URI)
    }

    it("accepts a YAML extension whatever its case") {
      ActiveYamlEditorUri.of(uriInput(name = "FLOW.YML")) shouldBe ActiveYamlEditorUri.Resolved(EXTERNAL_URI)
    }

    // Design §7.3a rule 8's last clause.
    it("rejects an active editor whose input names no file at all") {
      val input = mockk<IEditorInput>()
      every { input.name } returns YAML_NAME

      ActiveYamlEditorUri.of(input) shouldBe ActiveYamlEditorUri.Rejected.NO_FILE_URI
    }

    // Design §7.3a rule 2 percent-encodes non-ASCII as UTF-8 bytes when this value goes into the
    // query, and encoding an already-ASCII uri is the case design §21's manual item 12 exercises.
    it("gives back a uri in ascii form") {
      val uri = URI("file", null, "/home/alice/パイプ ライン&x.yml", null)
      val resolved = ActiveYamlEditorUri.of(uriInput(name = "パイプ ライン&x.yml", uri = uri))

      val fileUri = (resolved as ActiveYamlEditorUri.Resolved).fileUri
      fileUri shouldContain "%E3%83%91%E3%82%A4%E3%83%97"
      fileUri shouldNotContain "パ"
    }
  }
})
