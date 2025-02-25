package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.utils.CodeFormatter

class CodeSuggestionsProvider(private val codeFormatter: CodeFormatter) {
  private var counter = 1

  fun provide(): String {
    return codeFormatter.format("System.out.println(\"${counter++}\");")
  }
}
