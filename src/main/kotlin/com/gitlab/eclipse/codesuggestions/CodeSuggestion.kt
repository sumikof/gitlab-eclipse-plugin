package com.gitlab.eclipse.codesuggestions

data class CodeSuggestion(
  val streamId: String?,
  val trackingId: String?,
  val optionId: Int?,
  val text: String
)
