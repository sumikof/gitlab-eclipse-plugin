package com.gitlab.eclipse.lsp.diagnostics

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.eclipse.core.resources.IMarker
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

private fun diag(
  message: String = "m",
  severity: DiagnosticSeverity? = DiagnosticSeverity.Error,
  range: Range? = Range(Position(0, 0), Position(0, 1)),
  source: String? = "gitlab_security_scan",
) = Diagnostic().apply {
  this.setMessage(message)
  this.severity = severity
  if (range != null) this.range = range
  this.source = source
}

class DiagnosticMarkerAttributesTest : DescribeSpec({
  describe("TYPE") {
    it("is the exact literal machine-matched against plugin.xml in a later task") {
      DiagnosticMarkerAttributes.TYPE shouldBe "com.gitlab.eclipse.gitlab-eclipse-plugin.gitlabDiagnostic"
    }
  }

  describe("of") {
    it("maps all four severities and treats absent as info") {
      DiagnosticMarkerAttributes.of(diag(severity = DiagnosticSeverity.Error), 1L, 2L)[IMarker.SEVERITY] shouldBe IMarker.SEVERITY_ERROR
      DiagnosticMarkerAttributes.of(diag(severity = DiagnosticSeverity.Warning), 1L, 2L)[IMarker.SEVERITY] shouldBe IMarker.SEVERITY_WARNING
      DiagnosticMarkerAttributes.of(diag(severity = DiagnosticSeverity.Information), 1L, 2L)[IMarker.SEVERITY] shouldBe IMarker.SEVERITY_INFO
      DiagnosticMarkerAttributes.of(diag(severity = DiagnosticSeverity.Hint), 1L, 2L)[IMarker.SEVERITY] shouldBe IMarker.SEVERITY_INFO
      DiagnosticMarkerAttributes.of(diag(severity = null), 1L, 2L)[IMarker.SEVERITY] shouldBe IMarker.SEVERITY_INFO
    }
    it("converts a 0-based LSP line to a 1-based marker line") {
      DiagnosticMarkerAttributes.of(diag(range = Range(Position(4, 0), Position(4, 1))), 1L, 2L)[IMarker.LINE_NUMBER] shouldBe 5
    }
    it("clamps a negative line to 1") {
      DiagnosticMarkerAttributes.of(diag(range = Range(Position(-3, -1), Position(-3, -1))), 1L, 2L)[IMarker.LINE_NUMBER] shouldBe 1
    }
    it("falls back to line 1 when the range is absent") {
      DiagnosticMarkerAttributes.of(diag(range = null), 1L, 2L)[IMarker.LINE_NUMBER] shouldBe 1
    }
    it("collapses newlines and runs of whitespace in the message") {
      DiagnosticMarkerAttributes.of(diag(message = "name\n\n  long   description"), 1L, 2L)[IMarker.MESSAGE] shouldBe
        "name long description"
    }
    it("substitutes a placeholder for a blank message") {
      DiagnosticMarkerAttributes.of(diag(message = "   "), 1L, 2L)[IMarker.MESSAGE] shouldBe "(no message)"
    }
    it("surfaces MarkupContent text and collapses its whitespace the same way as plain text") {
      val markup = diag().apply { setMessage(MarkupContent("markdown", "name\n\n  long   description")) }
      DiagnosticMarkerAttributes.of(markup, 1L, 2L)[IMarker.MESSAGE] shouldBe "name long description"
    }
    it("always records source, generation and epoch") {
      val a = DiagnosticMarkerAttributes.of(diag(), 7L, 9L)
      a[DiagnosticMarkerAttributes.ATTR_SOURCE] shouldBe "gitlab_security_scan"
      a[DiagnosticMarkerAttributes.ATTR_GENERATION] shouldBe 7L
      a[DiagnosticMarkerAttributes.ATTR_EPOCH] shouldBe 9L
    }
    it("substitutes (unknown) when the source is absent") {
      DiagnosticMarkerAttributes.of(diag(source = null), 1L, 2L)[DiagnosticMarkerAttributes.ATTR_SOURCE] shouldBe "(unknown)"
    }
    it("omits the code attribute when absent and records it when present") {
      DiagnosticMarkerAttributes.of(diag(), 1L, 2L).containsKey(DiagnosticMarkerAttributes.ATTR_CODE) shouldBe false
      val withCode = diag().apply { setCode("SAST-1") }
      DiagnosticMarkerAttributes.of(withCode, 1L, 2L)[DiagnosticMarkerAttributes.ATTR_CODE] shouldBe "SAST-1"
    }
    it("never sets CHAR_START or CHAR_END") {
      val a = DiagnosticMarkerAttributes.of(diag(), 1L, 2L)
      a.containsKey(IMarker.CHAR_START) shouldBe false
      a.containsKey(IMarker.CHAR_END) shouldBe false
    }
  }
})
