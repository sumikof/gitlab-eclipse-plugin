package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.utils.PlatformUtils
import org.eclipse.swt.custom.StyledText
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

val codeSuggestionsModule = module {
  single<CodeSuggestionsManager> {
    CodeSuggestionsManager(get<PlatformUtils>()) { textWidget ->
      get<CodeSuggestionsSession> { parametersOf(textWidget) }
    }
  }

  factory<CodeSuggestionsSession> { (textWidget: StyledText) ->
    CodeSuggestionsSession(textWidget)
  }

  single<CodeSuggestionsStateService> { CodeSuggestionsStateService() }
}
