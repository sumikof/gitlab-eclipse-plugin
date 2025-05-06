package com.gitlab.eclipse

import com.gitlab.eclipse.authentication.OAuthTokenProvider
import com.gitlab.eclipse.authentication.authModule
import com.gitlab.eclipse.chat.chatModule
import com.gitlab.eclipse.codesuggestions.CodeSuggestionsManager
import com.gitlab.eclipse.codesuggestions.codeSuggestionsModule
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.GitLabLanguageServerProcessProvider
import com.gitlab.eclipse.lsp.languageServerModule
import com.gitlab.eclipse.lsp.plugins.pluginModule
import com.gitlab.eclipse.telemetry.telemetryModule
import com.gitlab.eclipse.utils.workspaceModule
import org.eclipse.core.commands.ParameterizedCommand
import org.eclipse.jface.bindings.Binding
import org.eclipse.jface.bindings.keys.KeyBinding
import org.eclipse.jface.bindings.keys.KeySequence
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.commands.ICommandService
import org.eclipse.ui.keys.IBindingService
import org.eclipse.ui.plugin.AbstractUIPlugin
import org.koin.core.context.startKoin
import org.osgi.framework.BundleContext

@Suppress("unused", "SpreadOperator")
class GitLabEclipseStartup : AbstractUIPlugin() {
  override fun start(context: BundleContext) {
    startKoin {
      modules(
        workspaceModule(context),
        authModule,
        languageServerModule,
        chatModule,
        pluginModule,
        codeSuggestionsModule,
        telemetryModule,
      )
    }

    service<GitLabLanguageServerProcessProvider>().start(context.bundle)
    service<OAuthTokenProvider>().startTokenRefreshTimer()

    val bindingService = PlatformUI.getWorkbench().getAdapter<IBindingService?>(IBindingService::class.java)
    val commandService = PlatformUI.getWorkbench().getAdapter<ICommandService?>(ICommandService::class.java)

    val bindings = mutableListOf(*bindingService.bindings)

    bindings.add(
      KeyBinding(
        KeySequence.getInstance("M1+ARROW_RIGHT"),
        ParameterizedCommand(
          commandService.getCommand("com.gitlab.eclipse.codesuggestions.acceptSuggestionLine"),
          null
        ),
        bindingService.activeScheme.id,
        "org.eclipse.ui.textEditorScope",
        null,
        null,
        null,
        Binding.USER
      )
    )

    bindingService.savePreferences(bindingService.activeScheme, bindings.toTypedArray())
  }

  override fun stop(context: BundleContext) {
    service<GitLabLanguageServerProcessProvider>().stop()
    service<CodeSuggestionsManager>().endAllSessions()
    service<OAuthTokenProvider>().stopTokenRefreshTimer()
  }
}
