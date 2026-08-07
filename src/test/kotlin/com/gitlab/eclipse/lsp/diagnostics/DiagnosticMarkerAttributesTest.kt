package com.gitlab.eclipse.lsp.diagnostics

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.eclipse.core.resources.IMarker
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/** Pins the cap: the production value may not drift without this test failing. */
private const val ATTRIBUTE_MAX_LENGTH = 2000

private const val EMOJI = "😀"

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
    // The workspace inspects the UTF-8 length of long string attributes and reports oversized ones
    // by asserting with a large slice of the value in the failure message, which would put the
    // diagnostic body into the error log. Capping the message keeps that path unreachable.
    it("truncates a message longer than the cap and marks it as truncated") {
      val message = DiagnosticMarkerAttributes.of(diag(message = "x".repeat(5000)), 1L, 2L)[IMarker.MESSAGE]

      message shouldBe "x".repeat(ATTRIBUTE_MAX_LENGTH - 1) + "…"
      (message as String).length shouldBe ATTRIBUTE_MAX_LENGTH
    }
    it("leaves a message at the cap untouched") {
      val message = "y".repeat(ATTRIBUTE_MAX_LENGTH)

      DiagnosticMarkerAttributes.of(diag(message = message), 1L, 2L)[IMarker.MESSAGE] shouldBe message
    }
    it("applies the cap to the collapsed message, not to the raw one") {
      val raw = "name" + " ".repeat(5000) + "description"

      DiagnosticMarkerAttributes.of(diag(message = raw), 1L, 2L)[IMarker.MESSAGE] shouldBe "name description"
    }
    // code and source are language server controlled too, so they need the same bound: an
    // oversized attribute value ends up in the platform's assertion message.
    it("caps an over-long code") {
      val code = DiagnosticMarkerAttributes.of(
        diag().apply { setCode("c".repeat(5000)) },
        1L,
        2L
      )[DiagnosticMarkerAttributes.ATTR_CODE] as String

      code.length shouldBe ATTRIBUTE_MAX_LENGTH
      code shouldBe "c".repeat(ATTRIBUTE_MAX_LENGTH - 1) + "…"
    }
    it("caps an over-long source") {
      val source = DiagnosticMarkerAttributes.of(
        diag(source = "s".repeat(5000)),
        1L,
        2L
      )[DiagnosticMarkerAttributes.ATTR_SOURCE] as String

      source.length shouldBe ATTRIBUTE_MAX_LENGTH
      source shouldBe "s".repeat(ATTRIBUTE_MAX_LENGTH - 1) + "…"
    }
    // Cutting between the two halves of a surrogate pair would leave a lone surrogate behind.
    it("never leaves half of a surrogate pair behind when truncating") {
      val raw = "a".repeat(ATTRIBUTE_MAX_LENGTH - 2) + EMOJI + "b".repeat(ATTRIBUTE_MAX_LENGTH)

      val message = DiagnosticMarkerAttributes.of(diag(message = raw), 1L, 2L)[IMarker.MESSAGE] as String

      message shouldBe "a".repeat(ATTRIBUTE_MAX_LENGTH - 2) + "…"
      message.none { it.isSurrogate() } shouldBe true
    }
    it("always records source, generation and epoch") {
      val a = DiagnosticMarkerAttributes.of(diag(), 7L, 9L)
      a[DiagnosticMarkerAttributes.ATTR_SOURCE] shouldBe "gitlab_security_scan"
      // IMarker attributes only accept String/Boolean/Integer at runtime (Eclipse Core Resources
      // MarkerInfo.checkValidAttribute throws IllegalArgumentException for anything else, notably
      // Long) — generation/epoch are Long across the API, so they must round-trip through String.
      a[DiagnosticMarkerAttributes.ATTR_GENERATION] shouldBe "7"
      a[DiagnosticMarkerAttributes.ATTR_EPOCH] shouldBe "9"
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
    it("only ever produces attribute values Eclipse's MarkerInfo.checkValidAttribute accepts") {
      // Mirrors org.eclipse.core.internal.resources.MarkerInfo.checkValidAttribute, which throws
      // IllegalArgumentException (not CoreException) for anything other than null/String/Boolean/
      // Integer. A real IMarker.setAttributes(...) call would blow up on any other runtime type
      // (e.g. Long), and our headless tests can't construct a real IMarker to catch that directly
      // — this test is the durable substitute so a future attribute addition can't reintroduce it.
      val withCode = diag().apply { setCode("SAST-1") }
      val attributes = DiagnosticMarkerAttributes.of(withCode, 7L, 9L)
      attributes.size shouldBe 7 // exercise every key, including the optional ATTR_CODE
      attributes.values.forEach { value ->
        (value is String || value is Boolean || value is Int) shouldBe true
      }
    }
  }
})
