package com.gitlab.eclipse.codesuggestions

import java.time.LocalTime
import java.time.format.DateTimeFormatter

class CodeSuggestionsProvider {
  private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

  fun provide(): String = timeFormatter.format(LocalTime.now())
}
