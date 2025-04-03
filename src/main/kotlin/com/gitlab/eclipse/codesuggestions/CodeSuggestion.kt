package com.gitlab.eclipse.codesuggestions

data class CodeSuggestion(
  val streamId: String?,
  val trackingId: String?,
  val optionId: Int?,
  var text: String
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is CodeSuggestion) return false

    return text == other.text
  }

  override fun hashCode(): Int {
    return text.hashCode()
  }
}
