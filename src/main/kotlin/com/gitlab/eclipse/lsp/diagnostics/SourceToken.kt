package com.gitlab.eclipse.lsp.diagnostics

/** 受理時に捕捉する source 失効世代。適用時に [DiagnosticGenerationRegistry.isTokenValid] で再検査する。 */
data class SourceToken(val source: String, val sourceEpoch: Long)
