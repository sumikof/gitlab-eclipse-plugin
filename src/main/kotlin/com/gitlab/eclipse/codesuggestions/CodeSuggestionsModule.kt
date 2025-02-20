package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import org.koin.dsl.module

val codeSuggestionsModule = module {
  single {
    CodeSuggestionsManager(get()) {
      get<CodeSuggestionsSession>()
    }
  }

  factory { CodeSuggestionsSession(get()) }

  single<CodeSuggestionsStateService> { CodeSuggestionsStateService() }
}
