package com.gitlab.eclipse

import com.gitlab.eclipse.api.apiModule
import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.api.http.relaxTunnelBasicAuthScheme
import com.gitlab.eclipse.authentication.OAuthTokenProvider
import com.gitlab.eclipse.authentication.authModule
import com.gitlab.eclipse.chat.chatModule
import com.gitlab.eclipse.ci.joblog.JobLogEditorOpener
import com.gitlab.eclipse.ci.joblog.JobLogGenerationRegistry
import com.gitlab.eclipse.ci.lint.CiLintGenerationRegistry
import com.gitlab.eclipse.ci.lint.MergedYamlEditorOpener
import com.gitlab.eclipse.codesuggestions.CodeSuggestionsManager
import com.gitlab.eclipse.codesuggestions.codeSuggestionsModule
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.GitLabLanguageServerProcessProvider
import com.gitlab.eclipse.lsp.languageServerModule
import com.gitlab.eclipse.lsp.plugins.pluginModule
import com.gitlab.eclipse.telemetry.telemetryModule
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.workspaceModule
import org.eclipse.core.commands.ParameterizedCommand
import org.eclipse.core.runtime.Platform
import org.eclipse.jface.bindings.Binding
import org.eclipse.jface.bindings.keys.KeyBinding
import org.eclipse.jface.bindings.keys.KeySequence
import org.eclipse.swt.SWTException
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.commands.ICommandService
import org.eclipse.ui.keys.IBindingService
import org.eclipse.ui.plugin.AbstractUIPlugin
import org.koin.core.context.startKoin
import org.osgi.framework.BundleContext

@Suppress("unused", "SpreadOperator")
class GitLabEclipseStartup : AbstractUIPlugin() {
  override fun start(context: BundleContext) {
    // Invalidate any CI lint generations left in `latest` by a previous stop (stop lets
    // in-flight lints finish) so their stale notifications/merged YAML cannot reapply.
    activateCiLint()

    // Set system property for log4j2 configuration to use Eclipse's state location
    // This ensures logs are written to a consistent location regardless of working directory
    val stateLocation = Platform.getStateLocation(context.bundle).toFile()
    System.setProperty("gitlab.plugin.state.dir", stateLocation.absolutePath)

    // Best-effort: allow Basic proxy auth over HTTPS CONNECT tunnels for the native REST
    // client. Read-once in java.net.http; reliable activation needs the eclipse.ini VM arg
    // -Djdk.http.auth.tunneling.disabledSchemes=  (see TunnelAuthSchemes.kt).
    relaxTunnelBasicAuthScheme()

    startKoin {
      modules(
        workspaceModule(context),
        authModule,
        languageServerModule,
        chatModule,
        pluginModule,
        codeSuggestionsModule,
        telemetryModule,
        apiModule,
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
    shutdownJobLog()
    service<GitLabLanguageServerProcessProvider>().stop()
    service<CodeSuggestionsManager>().endAllSessions()
    service<OAuthTokenProvider>().stopTokenRefreshTimer()
    service<GitLabHttpClient>().close()
  }

  private fun shutdownJobLog() {
    // (1) Disable late UI reflections/notifications from any in-flight trace fetch.
    JobLogGenerationRegistry.active = false
    CiLintGenerationRegistry.onDeactivate()
    // (2) Close in-memory trace editors + remove listeners on the UI thread; guard a disposed/absent display.
    try {
      val display = PlatformUI.getWorkbench().display
      if (!display.isDisposed) {
        display.syncExec {
          try {
            // No-op internally if the workbench is closing (editors die with it).
            JobLogEditorOpener.disposeAtShutdown()
            MergedYamlEditorOpener.disposeAtShutdown()
          } catch (_: SWTException) {
            /* Display disposed mid-shutdown: nothing left to release. */
          }
        }
      }
    } catch (e: Exception) {
      // Workbench/display already gone (headless or late shutdown): nothing to release. Never let stop throw.
      logger<GitLabEclipseStartup>().warn("Job-log shutdown skipped: workbench/display unavailable.", e)
    }
  }

  private fun activateCiLint() {
    try {
      val display = PlatformUI.getWorkbench().display
      if (!display.isDisposed) {
        display.syncExec {
          try {
            CiLintGenerationRegistry.onActivate()
          } catch (_: SWTException) {
            /* Display disposed mid-activation: registry stays at its initial fresh state. */
          }
        }
      }
    } catch (e: Exception) {
      // First start may run before the workbench/display exists; the registry's initial
      // state (active=true, epoch=0, empty latest) is already fresh. Never let start throw.
      logger<GitLabEclipseStartup>().warn("CI lint activation skipped: workbench/display unavailable.", e)
    }
  }
}
