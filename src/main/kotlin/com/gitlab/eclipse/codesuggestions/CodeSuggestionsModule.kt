package com.gitlab.eclipse.codesuggestions

import org.koin.dsl.module

val codeSuggestionsModule = module {
  single {
    CodeSuggestionsSession(get())
  }
}
