package com.gitlab.eclipse.codesuggestions

import org.koin.dsl.module

val codeSuggestionsModule = module {
  single {
    CodeSuggestionsManager(get()) {
      get<CodeSuggestionsSession>()
    }
  }

  factory { CodeSuggestionsSession(get()) }
}
