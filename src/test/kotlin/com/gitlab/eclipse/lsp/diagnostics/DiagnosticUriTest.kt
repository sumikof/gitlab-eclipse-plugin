package com.gitlab.eclipse.lsp.diagnostics

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class DiagnosticUriTest : DescribeSpec({
  describe("normalize") {
    it("treats file:/x and file:///x as the same key") {
      DiagnosticUri.normalize("file:/home/me/a.kt") shouldBe "/home/me/a.kt"
      DiagnosticUri.normalize("file:///home/me/a.kt") shouldBe "/home/me/a.kt"
    }
    it("decodes percent-encoding") {
      DiagnosticUri.normalize("file:/home/me/pro%20ject/%C3%9Cn.kt") shouldBe "/home/me/pro ject/Ün.kt"
    }
    it("upper-cases the Windows drive letter and keeps the leading slash form") {
      DiagnosticUri.normalize("file:/c:/src/A.kt") shouldBe "/C:/src/A.kt"
      DiagnosticUri.normalize("file:///C:/src/A.kt") shouldBe "/C:/src/A.kt"
    }
    it("returns null for non-file schemes") { DiagnosticUri.normalize("http://x/a") shouldBe null }
    it("returns null for malformed input") { DiagnosticUri.normalize("::::") shouldBe null }
    it("returns null for null and blank") {
      DiagnosticUri.normalize(null) shouldBe null
      DiagnosticUri.normalize("") shouldBe null
    }
  }
})
