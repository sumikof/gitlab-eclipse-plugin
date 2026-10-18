package com.gitlab.eclipse.lsp.utils

import com.gitlab.eclipse.lsp.utils.LanguageServerLanguage.languageId
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.eclipse.core.resources.IFile

class LanguageServerLanguageTest : DescribeSpec({
  fun file(name: String, fileExtension: String?) = mockk<IFile> {
    every { this@mockk.name } returns name
    every { this@mockk.fileExtension } returns fileExtension
  }

  describe("languageId") {
    it("resolves a C# file to the csharp language id the language server supports") {
      file("Program.cs", "cs").languageId shouldBe "csharp"
    }

    describe("resolves supported languages whose id differs from their extension") {
      withData(
        nameFn = { (extension, languageId) -> "$extension -> $languageId" },
        "py" to "python",
        "kt" to "kotlin",
        "kts" to "kotlin",
        "ts" to "typescript",
        "js" to "javascript",
        "rb" to "ruby",
        "rs" to "rust",
      ) { (extension, languageId) ->
        file("file.$extension", extension).languageId shouldBe languageId
      }
    }

    it("matches the extension regardless of its case") {
      file("MAIN.PY", "PY").languageId shouldBe "python"
    }

    it("lowercases an unknown extension the way the language server normalizes additional languages") {
      file("build.ZIG", "ZIG").languageId shouldBe "zig"
    }

    it("falls back to the lowercased file name when the file has no extension") {
      file("Makefile", null).languageId shouldBe "makefile"
    }

    it("falls back to the lowercased file name when the extension is empty") {
      file("Notes.", "").languageId shouldBe "notes."
    }

    it("uses the extension of a dot file as its language id") {
      file(".gitignore", "gitignore").languageId shouldBe "gitignore"
    }
  }
})
