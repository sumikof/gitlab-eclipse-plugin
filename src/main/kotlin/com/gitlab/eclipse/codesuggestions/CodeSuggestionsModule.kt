package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.utils.TextEditorProvider
import kotlinx.coroutines.CoroutineScope
import org.eclipse.swt.custom.StyledText
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

val codeSuggestionsModule = module {
  single<CodeSuggestionsManager> {
    CodeSuggestionsManager(get<TextEditorProvider>()) { textWidget ->
      get<CodeSuggestionsSession> { parametersOf(textWidget) }
    }
  }

  factory<CodeSuggestionsSession> { (textWidget: StyledText) ->
    CodeSuggestionsSession(
      textWidget,
      get<CoroutineScope>()
    )
  }

  single<CodeSuggestionsStateService> { CodeSuggestionsStateService() }
}
