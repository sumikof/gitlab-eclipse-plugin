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
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import com.gitlab.eclipse.lsp.languageServerModule
import com.gitlab.eclipse.lsp.plugins.pluginModule
import com.gitlab.eclipse.mergerequests.discussions.DiscussionGenerationRegistry
import com.gitlab.eclipse.security.SecurityScanLifecycle
import com.gitlab.eclipse.security.SecurityScanSaveListener
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
    // Set system property for log4j2 configuration to use Eclipse's state location
    // This ensures logs are written to a consistent location regardless of working directory
    val stateLocation = Platform.getStateLocation(context.bundle).toFile()
    System.setProperty("gitlab.plugin.state.dir", stateLocation.absolutePath)

    // Invalidate any CI lint / discussion / job-log generations left in `latest` by a previous
    // stop (stop lets in-flight work finish) so their stale results cannot reapply, and restore
    // each registry's `active` flag that the stop hook cleared.
    // Runs after the state-dir property is set so a degraded-path log4j2 touch here
    // (this warn) cannot pin a misconfigured log location for the whole session.
    activateGenerationRegistries()
    // Deliberately NOT inside activateGenerationRegistries()' display.syncExec: publishing and
    // removing diagnostics markers never touches the UI thread, and putting this there would both
    // invent a dependency the feature does not have and skip activation entirely on a start that
    // runs before the workbench exists. Advancing the epoch here also strands anything a previous
    // session left behind, exactly as the two registries above do.
    DiagnosticGenerationRegistry.onActivate()

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
    installSecurityScanSaveListener()

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
    // Step 1 of the diagnostics shutdown: stopping the language server runs the connection teardown
    // (advance the epoch, cancel the waiting commands, remove the dead connection's markers) through
    // the same SecurityScanLifecycle.onServerStopped() the crash path uses. It has to happen BEFORE
    // shutdownDiagnostics() below: the waiters are dropped and their deadlines released while there
    // is still a workbench to show it, instead of leaving in-flight deadline jobs to notify one that
    // is already gone.
    stopLanguageServer()
    shutdownDiagnostics()
    service<CodeSuggestionsManager>().endAllSessions()
    service<OAuthTokenProvider>().stopTokenRefreshTimer()
    service<GitLabHttpClient>().close()
  }

  private fun shutdownJobLog() {
    // (1) Disable late UI reflections/notifications from any in-flight trace fetch.
    JobLogGenerationRegistry.active = false
    // (2) Close in-memory trace editors + remove listeners on the UI thread; guard a disposed/absent display.
    try {
      val display = PlatformUI.getWorkbench().display
      if (!display.isDisposed) {
        display.syncExec {
          try {
            // Deactivate CI lint ON the UI thread, before editor disposal: every CI-lint
            // reflect/notify runnable runs on the UI thread, so flipping `active` here totally
            // orders the deactivation with each runnable's gate-check-then-act — no runnable can
            // pass its gate and then act after deactivation (a bare off-thread write left that
            // torn window open). If the display is unavailable/disposed this syncExec is skipped
            // and `active` stays true, which is inert: without a live display no reflect/notify
            // runnable can run (currentDisplay throws IllegalStateException, asyncExec throws
            // SWTException — both caught as no-ops), and the command handlers are unregistered
            // once the bundle stops, so nothing reads `active` after a display-less stop.
            CiLintGenerationRegistry.onDeactivate()
            // Same reasoning as above: every discussion reflect runnable runs on the UI thread
            // and gates on `active`, so the flip must happen here, not as a bare off-thread write.
            DiscussionGenerationRegistry.onDeactivate()
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

  /**
   * Step 1 of the diagnostics shutdown, guarded.
   *
   * Reaching the provider goes through Koin, which can be closed by the time a late stop runs, and
   * the stop itself walks a language server that may be half gone. Unguarded, either would take
   * [shutdownDiagnostics] and everything after it down with it — the markers would survive and the
   * save trigger would stay attached to every document provider it reached, which outlive this
   * bundle. The connection teardown inside the provider is in a `finally`, so it still runs.
   *
   * Only the exception's class name is recorded: this path quotes language server state. Never let
   * stop throw.
   */
  private fun stopLanguageServer() {
    try {
      service<GitLabLanguageServerProcessProvider>().stop()
    } catch (e: Exception) {
      logger<GitLabEclipseStartup>().warn("Language server shutdown skipped: ${e::class.simpleName}")
    }
  }

  /**
   * Steps 2-4 of the diagnostics shutdown: stop applying diagnostics, remove every marker this
   * plugin published, and detach the save trigger from every document provider it reached.
   *
   * Marker removal resolves the workspace root on this thread, which raises once the workspace is
   * closed, and the save trigger's detach walks a workbench that is usually half gone by now. Both
   * are contained inside [SecurityScanLifecycle]; this second layer is here for the same reason
   * [shutdownJobLog] has one. Never let stop throw.
   */
  private fun shutdownDiagnostics() {
    try {
      SecurityScanLifecycle.onBundleStopping()
    } catch (e: Exception) {
      logger<GitLabEclipseStartup>().warn("Diagnostics shutdown skipped.", e)
    }
  }

  /**
   * Attaches the security scan save trigger, on the UI thread and after Koin is up: it walks the
   * workbench's open editors, and the listener it registers is resolved from the container.
   *
   * A workbench that is not up yet is not a failure — the listener waits for a window to open — and
   * a start that cannot reach one at all must not take the whole bundle down with it.
   */
  private fun installSecurityScanSaveListener() {
    try {
      val display = PlatformUI.getWorkbench().display
      if (!display.isDisposed) {
        display.syncExec {
          try {
            service<SecurityScanSaveListener>().install()
          } catch (_: SWTException) {
            /* Display disposed mid-install: nothing was attached, so nothing leaks. */
          }
        }
      }
    } catch (e: Exception) {
      // Never let start throw. install() is idempotent and leaves its done-flag down when the
      // workbench itself was unreachable, but this is its only call site, so nothing ever calls
      // it again: landing here means the save trigger stays off for the rest of the session.
      // install() does NOT wait for a page: on a workbench with zero windows it marks itself done
      // having attached nothing — deliberately — and the window listener it registered does the
      // attaching as windows open.
      logger<GitLabEclipseStartup>().warn("Security scan save trigger not installed: workbench unavailable.", e)
    }
  }

  private fun activateGenerationRegistries() {
    try {
      val display = PlatformUI.getWorkbench().display
      if (!display.isDisposed) {
        display.syncExec {
          try {
            CiLintGenerationRegistry.onActivate()
            DiscussionGenerationRegistry.onActivate()
            // The stop hook clears JobLogGenerationRegistry.active and nothing else restores it:
            // without this call a stop->start cycle in the same class loader leaves every
            // shouldAct() false forever and "Display Log" silently stops reflecting.
            JobLogGenerationRegistry.onActivate()
          } catch (_: SWTException) {
            /* Display disposed mid-activation: registries stay at their initial fresh state. */
          }
        }
      }
    } catch (e: Exception) {
      // First start may run before the workbench/display exists; each registry's initial
      // state (active=true, epoch=0, empty latest) is already fresh. Never let start throw.
      logger<GitLabEclipseStartup>().warn("Generation registry activation skipped: workbench/display unavailable.", e)
    }
  }
}
