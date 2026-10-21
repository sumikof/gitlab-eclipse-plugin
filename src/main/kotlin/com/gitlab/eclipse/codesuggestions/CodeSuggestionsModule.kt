package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.authentication.AuthenticationSourceProvider
import com.gitlab.eclipse.codesuggestions.annotation.CodeSuggestionsSessionAnnotationManager
import com.gitlab.eclipse.codesuggestions.languages.CodeSuggestionsLanguageService
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsWarningNotifier
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsWarningPolicy
import com.gitlab.eclipse.codesuggestions.status.DuoStatusInputsProvider
import com.gitlab.eclipse.codesuggestions.status.DuoStatusRefresher
import com.gitlab.eclipse.codesuggestions.status.ScopedPreferenceWarningAccess
import com.gitlab.eclipse.codesuggestions.status.UPGRADE_DOCS_URL
import com.gitlab.eclipse.codesuggestions.status.authStateOrUnknown
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.ShowDocumentLauncher
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerLifecycleStore
import com.gitlab.eclipse.preferences.PreferenceConstants
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
    // Any known/primary-check change re-renders both the trim item and the status menu label.
    val refresher by lazy { get<DuoStatusRefresher>() }
    CodeSuggestionsStateService(
      currentSession = { wrapper.currentSnapshot?.session },
      onStatusChanged = { refresher.request() },
      onFeatureState = { session, checks -> notifier.onFeatureState(session, checks) },
    )
  }

  // QCHAT-07 (design §8): the trim item and the menu label read the same in-memory inputs.
  single<DuoStatusInputsProvider> {
    val store = get<LanguageServerLifecycleStore>()
    val preferences = get<ScopedPreferenceStore>()
    val languages = get<CodeSuggestionsLanguageService>()
    val stateService by lazy { get<CodeSuggestionsStateService>() }
    val authSource by lazy { get<AuthenticationSourceProvider>() }
    DuoStatusInputsProvider(
      lifecycle = store::snapshot,
      // In-memory state only (F5); the workbench-created provider is resolved on first render.
      authState = { authStateOrUnknown { authSource.authState } },
      codeSuggestionsEnabled = { preferences.getBoolean(PreferenceConstants.CODE_SUGGESTIONS_ENABLED) },
      featureStateKnown = { stateService.featureStateKnown },
      primaryCheckId = { stateService.primaryCheckId },
      isLanguageEnabled = languages::isEnabled,
    )
  }

  // Registered as lifecycle listener, installed and disposed by the plugin lifecycle (T6).
  single<DuoStatusRefresher> {
    val store = get<LanguageServerLifecycleStore>()
    val preferences = get<ScopedPreferenceStore>()
    DuoStatusRefresher(
      isStoreClosed = { store.isClosed },
      authSource = { get<AuthenticationSourceProvider>() },
      preferenceStore = { preferences },
    )
  }
}
