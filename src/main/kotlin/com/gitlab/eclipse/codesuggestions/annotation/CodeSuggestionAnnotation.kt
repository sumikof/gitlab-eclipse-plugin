package com.gitlab.eclipse.codesuggestions.annotation

import org.eclipse.jface.text.source.Annotation

enum class CodeSuggestionAnnotationType(val message: String) {
  LOADING("Loading Code Suggestions..."),
  READY("Code Suggestions Ready")
}

data class CodeSuggestionAnnotation(
  val type: CodeSuggestionAnnotationType
) : Annotation("codeSuggestionsMarker", false, type.message)
