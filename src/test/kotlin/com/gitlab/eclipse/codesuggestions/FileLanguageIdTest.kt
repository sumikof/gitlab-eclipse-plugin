package com.gitlab.eclipse.codesuggestions

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.eclipse.core.resources.IFile
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IFileEditorInput

/** Kept apart from `CodeSuggestionsSessionTest`, which needs the SWT natives to instantiate. */
class FileLanguageIdTest : DescribeSpec({
  describe("fileLanguageIdOf") {
    it("uses the workspace file's language id, as didOpen sends it") {
      val file = mockk<IFile> {
        every { fileExtension } returns "TXT"
        every { name } returns "notes.TXT"
      }
      val input = mockk<IFileEditorInput> { every { this@mockk.file } returns file }

      fileLanguageIdOf(input) shouldBe "txt"
    }

    it("maps a supported extension to its language id") {
      val file = mockk<IFile> {
        every { fileExtension } returns "kt"
        every { name } returns "Main.kt"
      }
      val input = mockk<IFileEditorInput> { every { this@mockk.file } returns file }

      fileLanguageIdOf(input) shouldBe "kotlin"
    }

    it("is null for an editor without a workspace file") {
      fileLanguageIdOf(mockk<IEditorInput>()) shouldBe null
      fileLanguageIdOf(null) shouldBe null
    }
  }
})
