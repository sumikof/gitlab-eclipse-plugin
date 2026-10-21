package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.codesuggestions.annotation.CodeSuggestionsSessionAnnotationManager
import com.gitlab.eclipse.codesuggestions.languages.CodeSuggestionsLanguageService
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsWarningNotifier
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsWarningPolicy
import com.gitlab.eclipse.codesuggestions.status.ScopedPreferenceWarningAccess
import com.gitlab.eclipse.codesuggestions.status.UPGRADE_DOCS_URL
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.ShowDocumentLauncher
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerLifecycleStore
import com.gitlab.eclipse.utils.PlatformUtils
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.eclipse.ui.texteditor.ITextEditor
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module
import java.time.LocalDate

val codeSuggestionsModule = module {
  single<CodeSuggestionsLanguageService> { CodeSuggestionsLanguageService(get()) }

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
      telemetryService = get(),
      fileLanguageId = { fileLanguageIdOf(textEditor.editorInput) },
    )
  }

  single<CodeSuggestionsProvider> {
    CodeSuggestionsProvider(
      gitLabLanguageServerWrapper = get(),
      codeFormatter = get(),
      codeSuggestionsLanguageService = get(),
    )
  }

  // CS-08: the policy decides, the notifier displays; both gate on the current LS session.
  single<CodeSuggestionsWarningPolicy> {
    CodeSuggestionsWarningPolicy(ScopedPreferenceWarningAccess(get<ScopedPreferenceStore>()), LocalDate::now)
  }

  single<CodeSuggestionsWarningNotifier> {
    val wrapper = get<GitLabLanguageServerWrapper>()
    val store = get<LanguageServerLifecycleStore>()
    val launcher = get<ShowDocumentLauncher>()
    CodeSuggestionsWarningNotifier(
      policy = get(),
      currentSession = { wrapper.currentSnapshot?.session },
      isClosed = { store.isClosed },
      openUpgradeDocs = { launcher.show(UPGRADE_DOCS_URL) },
    )
  }

  single<CodeSuggestionsStateService> {
    val wrapper = get<GitLabLanguageServerWrapper>()
    // Resolved on the first feature state, not when the state service is built.
    val notifier by lazy { get<CodeSuggestionsWarningNotifier>() }
    CodeSuggestionsStateService(
      currentSession = { wrapper.currentSnapshot?.session },
      onFeatureState = { session, checks -> notifier.onFeatureState(session, checks) },
    )
  }
}
