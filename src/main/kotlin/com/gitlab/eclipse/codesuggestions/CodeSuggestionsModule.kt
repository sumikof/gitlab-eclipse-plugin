package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.codesuggestions.annotation.CodeSuggestionsSessionAnnotationManager
import com.gitlab.eclipse.codesuggestions.languages.CodeSuggestionsEnabledLanguageService
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.utils.PlatformUtils
import org.eclipse.ui.texteditor.ITextEditor
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

val codeSuggestionsModule = module {
  single<CodeSuggestionsEnabledLanguageService> { CodeSuggestionsEnabledLanguageService(get()) }

  single<CodeSuggestionsManager>(createdAtStart = true) {
    CodeSuggestionsManager(get<PlatformUtils>()) { textEditor ->
      get<CodeSuggestionsSession> { parametersOf(textEditor) }
    }
  }

  single<StreamingCodeSuggestionsManager> {
    StreamingCodeSuggestionsManager(get(), get())
  }

  factory<CodeSuggestionsSession> { (textEditor: ITextEditor) ->
    val platformUtils = get<PlatformUtils>()

    val textWidget = checkNotNull(platformUtils.getTextWidget(textEditor))
    val document = checkNotNull(platformUtils.getDocument(textEditor))

    CodeSuggestionsSession(
      textWidget = textWidget,
      document = document,
      codeSuggestionsProvider = get(),
      codeSuggestionsRenderer = CodeSuggestionsRenderer(document, textWidget),
      annotationManager = CodeSuggestionsSessionAnnotationManager(textEditor),
      streamingCodeSuggestionsManager = get(),
      codeSuggestionsCommandContext = CodeSuggestionsCommandContext(get()),
      coroutineScope = get(),
      telemetryService = get()
    )
  }

  single<CodeSuggestionsProvider> {
    CodeSuggestionsProvider(
      gitLabLanguageServerWrapper = get(),
      codeFormatter = get()
    )
  }

  single<CodeSuggestionsStateService> { CodeSuggestionsStateService() }
}
