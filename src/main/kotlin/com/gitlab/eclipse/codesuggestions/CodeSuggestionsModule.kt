package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.utils.PlatformUtils
import org.eclipse.jface.text.IDocument
import org.eclipse.swt.custom.StyledText
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

val codeSuggestionsModule = module {
  single<CodeSuggestionsManager>(createdAtStart = true) {
    CodeSuggestionsManager(get<PlatformUtils>()) { textWidget, document ->
      get<CodeSuggestionsSession> { parametersOf(textWidget, document) }
    }
  }

  single<CodeSuggestionsProvider> { CodeSuggestionsProvider(get()) }

  factory<CodeSuggestionsSession> { (textWidget: StyledText, document: IDocument) ->
    CodeSuggestionsSession(
      textWidget = textWidget,
      document = document,
      codeSuggestionsProvider = get(),
      codeSuggestionsRenderer = CodeSuggestionsRenderer(textWidget)
    )
  }

  single<CodeSuggestionsStateService> { CodeSuggestionsStateService() }
}
