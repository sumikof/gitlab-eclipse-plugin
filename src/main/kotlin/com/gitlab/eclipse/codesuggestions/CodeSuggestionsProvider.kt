package com.gitlab.eclipse.codesuggestions

import java.time.LocalTime
import java.time.format.DateTimeFormatter

class CodeSuggestionsProvider {
  private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

  private var counter = 1

  fun provide(): String = "Req#${counter++} ${timeFormatter.format(LocalTime.now())}"
}
