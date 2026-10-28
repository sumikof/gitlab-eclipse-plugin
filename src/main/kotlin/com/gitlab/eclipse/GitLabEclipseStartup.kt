package com.gitlab.eclipse

import com.gitlab.eclipse.api.apiModule
import com.gitlab.eclipse.api.http.GitLabHttpClient
import com.gitlab.eclipse.api.http.relaxTunnelBasicAuthScheme
import com.gitlab.eclipse.authentication.OAuthTokenProvider
import com.gitlab.eclipse.authentication.authModule
import com.gitlab.eclipse.chat.chatModule
import com.gitlab.eclipse.chat.quickchat.QuickChatRuntimeLifecycle
import com.gitlab.eclipse.chat.quickchat.ui.QuickChatPopups
import com.gitlab.eclipse.ci.joblog.JobLogEditorOpener
import com.gitlab.eclipse.ci.joblog.JobLogGenerationRegistry
import com.gitlab.eclipse.ci.lint.CiLintGenerationRegistry
import com.gitlab.eclipse.ci.lint.MergedYamlEditorOpener
import com.gitlab.eclipse.codesuggestions.CodeSuggestionsManager
import com.gitlab.eclipse.codesuggestions.codeSuggestionsModule
import com.gitlab.eclipse.codesuggestions.status.DuoStatusRefresher
import com.gitlab.eclipse.currentbranch.CurrentBranchStateService
import com.gitlab.eclipse.currentbranch.currentBranchModule
import com.gitlab.eclipse.diagnostics.DiagnosticsLog
import com.gitlab.eclipse.diagnostics.configuredSecrets
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.GitLabLanguageServerProcessProvider
import com.gitlab.eclipse.lsp.InstallProgressJobs
import com.gitlab.eclipse.lsp.diagnostics.DiagnosticGenerationRegistry
import com.gitlab.eclipse.lsp.languageServerModule
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerFailureNotifier
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerLifecycleListener
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerLifecycleStore
import com.gitlab.eclipse.lsp.messages.LanguageServerMessagePresenter
import com.gitlab.eclipse.lsp.plugins.pluginModule
import com.gitlab.eclipse.mergerequests.diff.MrFileDiffEntryPoint
import com.gitlab.eclipse.mergerequests.discussions.DiscussionGenerationRegistry
import com.gitlab.eclipse.mergerequests.review.MrThreadPopups
import com.gitlab.eclipse.mergerequests.review.ReviewSessionRegistry
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.security.SecurityScanLifecycle
import com.gitlab.eclipse.security.SecurityScanSaveListener
import com.gitlab.eclipse.snippets.PatchQuarantine
import com.gitlab.eclipse.telemetry.telemetryModule
import com.gitlab.eclipse.utils.DebugLogging
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.workspaceModule
import com.gitlab.eclipse.views.webview.agentplatform.AgentPlatformViews
import org.eclipse.core.commands.ParameterizedCommand
import org.eclipse.core.runtime.Platform
import org.eclipse.jface.bindings.Binding
import org.eclipse.jface.bindings.keys.KeyBinding
import org.eclipse.jface.bindings.keys.KeySequence
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTException
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.commands.ICommandService
import org.eclipse.ui.keys.IBindingService
import org.eclipse.ui.plugin.AbstractUIPlugin
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.osgi.framework.Bundle
import org.osgi.framework.BundleContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One turn on the UI thread, as the stop and start hooks take it: runs [syncExec]'s block there and
 * waits for it. Returns false, without running the block, when the display is already disposed;
 * throws when the workbench or its display cannot be reached at all (`IllegalStateException`) or
 * the display is disposed while the caller waits (`SWTException`). A seam: tests replace it.
 */
internal fun interface UiTurn {
  fun syncExec(block: () -> Unit): Boolean
}

/** The workbench display's [UiTurn]. Inline when the caller is already the UI thread, as syncExec is. */
internal object WorkbenchUiTurn : UiTurn {
  override fun syncExec(block: () -> Unit): Boolean {
    val display = PlatformUI.getWorkbench().display
    if (display.isDisposed) return false
    display.syncExec { block() }
    return true
  }
}

/** A named cleanup or activation step; the name is what a failure logs. */
internal class LifecycleStep(val name: String, val run: () -> Unit)

/**
 * Runs [block] behind its own exception boundary, so one failing [step] never skips the next one
 * and never lets a lifecycle hook throw. Only the exception's class name is logged — never its
 * message or stack: these paths quote language server state, URLs and tokens. The log call is
 * itself contained, since a stop late enough to fail here can find the log gone too. `Error` is
 * deliberately not caught.
 */
internal inline fun bestEffort(step: String, phase: String = "shutdown", block: () -> Unit) {
  try {
    block()
  } catch (e: Exception) {
    runCatching { logger<GitLabEclipseStartup>().warn("$step $phase skipped: ${e::class.simpleName}") }
  }
}

/**
 * What [GitLabEclipseStartup] attached to the [LanguageServerLifecycleStore], so the stop hook's UI
 * turn can take it back (design §15 I6). Process-wide, like the generation registries.
 */
internal object LifecycleListenerRegistrations {
  private val registrations = CopyOnWriteArrayList<AutoCloseable>()

  /** Attaches each listener behind its own boundary: one that cannot be resolved never skips the next. */
  fun attach(
    store: LanguageServerLifecycleStore,
    listeners: List<Pair<String, () -> LanguageServerLifecycleListener>>,
  ) {
    listeners.forEach { (name, listener) ->
      bestEffort(name, "activation") { registrations += store.addListener(listener()) }
    }
  }

  /** Removes everything [attach] added. Idempotent; each removal contained. */
  fun closeAll() {
    val attached = registrations.toList()
    registrations.clear()
    attached.forEach { registration -> bestEffort("Lifecycle listener") { registration.close() } }
  }
}

/** Why a hook's UI turn was skipped: INFO on the normal exit path, WARN otherwise. Class name only; contained. */
private fun logUiTurnSkipped(phase: String, e: Exception, normalExit: Boolean) {
  runCatching {
    val message = "UI $phase skipped: ${e::class.simpleName}"
    if (normalExit) logger<GitLabEclipseStartup>().info(message) else logger<GitLabEclipseStartup>().warn(message)
  }
}

@Suppress("unused", "SpreadOperator")
class GitLabEclipseStartup : AbstractUIPlugin() {
  /** How the hooks reach the UI thread. Defaults to the workbench display; tests replace it. */
  internal var uiTurn: UiTurn = WorkbenchUiTurn

  /** What the stop hook's UI turn runs, in order. Defaults to [productionUiShutdownSteps]. */
  internal var uiShutdownSteps: List<LifecycleStep> = productionUiShutdownSteps()

  /** What the start hook's UI turn runs, in order. Defaults to [productionActivationSteps]. */
  internal var activationSteps: List<LifecycleStep> = productionActivationSteps()

  /** The lifecycle listeners attached before the language server starts. Defaults to [productionLifecycleListeners]. */
  internal var lifecycleListeners: List<Pair<String, () -> LanguageServerLifecycleListener>> =
    productionLifecycleListeners()

  /** What runs on the UI thread once the language server started. Defaults to [productionDuoStatusActivationSteps]. */
  internal var duoStatusActivationSteps: List<LifecycleStep> = productionDuoStatusActivationSteps()

  /** Hands a runnable to the UI thread without waiting (`asyncExec`). A seam: tests replace it. */
  internal var uiDispatch: (Runnable) -> Unit = { PlatformUI.getWorkbench().display.asyncExec(it) }

  override fun start(context: BundleContext) {
    // Set system property for log4j2 configuration to use Eclipse's state location
    // This ensures logs are written to a consistent location regardless of working directory
    val stateLocation = Platform.getStateLocation(context.bundle).toFile()
    System.setProperty("gitlab.plugin.state.dir", stateLocation.absolutePath)

    // Installed here, as early as the state directory itself, so the diagnostics export can show
    // what happened during start — which is exactly when the interesting failures happen. The
    // secrets lambda is evaluated per log line and contains its own failures, so installing it
    // before Koin is up costs nothing (design §9.1).
    installDiagnosticsLogTap(context)

    // Invalidate any CI lint / discussion / job-log generations left in `latest` by a previous
    // stop (stop lets in-flight work finish) so their stale results cannot reapply, and restore
    // each registry's `active` flag that the stop hook cleared.
    // Runs after the state-dir property is set so a degraded-path log4j2 touch here
    // (this warn) cannot pin a misconfigured log location for the whole session.
    // Also after the state-dir property: the backup area lives under it. A crashed apply's residue
    // is deliberately kept until it expires, so this only removes what is past the retention window.
    sweepPatchBackups()

    activateGenerationRegistries()
    // Deliberately NOT inside activateGenerationRegistries()' UI turn: publishing and
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
        currentBranchModule,
      )
    }

    startLanguageServer(context.bundle)
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

  /**
   * Every step runs behind its own exception boundary ([bestEffort]), in this fixed order; a step
   * that throws is logged by class name and the next one still runs. stop never throws.
   */
  override fun stop(context: BundleContext) {
    // First, and independent of every step below: a step that throws must not leave the periodic
    // OAuth refresh scheduled after this bundle has stopped. The service is resolved inside the
    // boundary: Koin can be closed, or the singleton fail to build, by the time a late stop runs.
    bestEffort("OAuth refresh timer") { service<OAuthTokenProvider>().stopTokenRefreshTimer() }
    uninstallDiagnosticsLogTap(context)
    // Design §5.5: the first language server step. A closed store drops its listeners and ignores
    // every later mutation, so the explicit stop below publishes no transition: no failure popup, no
    // status refresh. The listeners' UI runnables already queued re-check isClosed and do nothing.
    bestEffort("Language server lifecycle") { service<LanguageServerLifecycleStore>().close() }
    // The stop no longer reaches the presenter as a transition, so its open showMessageRequests are
    // answered (null, off this thread) here; their dialogs close in a later UI turn.
    bestEffort("Language server messages") { service<LanguageServerMessagePresenter>().sessionsEndedExcept(null) }
    runUiTurn("shutdown", uiShutdownSteps)
    // Design §17 step 2: the UI turn above already discarded every Quick Chat popup on the UI
    // thread (each session.end() closed its send gate and cancelled its job); only now is the Quick
    // Chat scope cancelled, without joining. Never throws; bounded anyway, like every other step.
    bestEffort("Quick Chat runtime") { QuickChatRuntimeLifecycle.closeIfCreated() }
    // Step 1 of the diagnostics shutdown: stopping the language server runs the connection teardown
    // (advance the epoch, cancel the waiting commands, remove the dead connection's markers) through
    // the same SecurityScanLifecycle.onServerStopped() the crash path uses. It has to happen BEFORE
    // shutdownDiagnostics() below: the waiters are dropped and their deadlines released while there
    // is still a workbench to show it, instead of leaving in-flight deadline jobs to notify one that
    // is already gone.
    stopLanguageServer()
    // Design §3.4: the install ends inside provider.stop(), closing its progress session; this only
    // makes sure no install progress outlives the bundle when that stop could not run.
    bestEffort("Language server install progress") { InstallProgressJobs.cancelAll() }
    shutdownDiagnostics()
    // Still on the stop thread, as before; only contained now, so a session that fails to end (or a
    // manager that cannot be resolved) no longer leaves the HTTP client open.
    bestEffort("Code suggestion sessions") { service<CodeSuggestionsManager>().endAllSessions() }
    bestEffort("HTTP client") { service<GitLabHttpClient>().close() }
  }

  /**
   * Takes one [uiTurn] and runs [steps] in it, each behind its own boundary, so that a step that
   * throws — an SWT failure or any other — never skips the ones after it.
   *
   * Not reaching the UI thread is the normal exit path, not a failure: `IDEApplication` disposes
   * the display before the bundles stop. So a disposed display is silent, and a workbench that
   * cannot be reached or a display disposed while waiting is logged at INFO; anything else at WARN.
   * Class name only, as everywhere in this hook.
   */
  private fun runUiTurn(phase: String, steps: List<LifecycleStep>) {
    try {
      uiTurn.syncExec { steps.forEach { step -> bestEffort(step.name, phase, step.run) } }
    } catch (e: IllegalStateException) {
      logUiTurnSkipped(phase, e, normalExit = true)
    } catch (e: SWTException) {
      logUiTurnSkipped(phase, e, normalExit = e.code == SWT.ERROR_DEVICE_DISPOSED)
    } catch (e: Exception) {
      logUiTurnSkipped(phase, e, normalExit = false)
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
    bestEffort("Language server") { service<GitLabLanguageServerProcessProvider>().stop() }
  }

  /**
   * Steps 2-4 of the diagnostics shutdown: stop applying diagnostics, remove every marker this
   * plugin published, and detach the save trigger from every document provider it reached.
   *
   * Marker removal resolves the workspace root on this thread, which raises once the workspace is
   * closed, and the save trigger's detach walks a workbench that is usually half gone by now. Both
   * are contained inside [SecurityScanLifecycle]; this second layer is here for the same reason
   * every other step has one. Class name only. Never let stop throw.
   */
  private fun shutdownDiagnostics() {
    bestEffort("Diagnostics") { SecurityScanLifecycle.onBundleStopping() }
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
      // Class name only (AC-G1): the exception's message and stack are not for the Error Log.
      logger<GitLabEclipseStartup>().warn(
        "Security scan save trigger not installed: workbench unavailable (${e.javaClass.simpleName}).",
      )
    }
  }

  /**
   * Starts capturing this plugin's log for the diagnostics export, and points the debug-logging
   * gate at the preference store (design §5.1.1, §9.1).
   *
   * Both are best-effort. A bundle that cannot capture its own log is still a working plugin, and
   * taking start down over a diagnostics feature would be exactly backwards. Nothing here logs on
   * the failure path — a failure to install a log listener is the one thing that must not be
   * reported through the log listener.
   */
  private fun installDiagnosticsLogTap(context: BundleContext) {
    runCatching {
      DebugLogging.isEnabled = {
        runCatching { service<ScopedPreferenceStore>().getBoolean(PreferenceConstants.DEBUG_LOGGING) }
          .getOrDefault(false)
      }
      DiagnosticsLog.install(Platform.getLog(context.bundle), ::configuredSecrets)
    }
  }

  /** Detaches the log listener. Contained, and silent, for the reasons above. Never lets stop throw. */
  private fun uninstallDiagnosticsLogTap(context: BundleContext) {
    runCatching { DiagnosticsLog.uninstall(Platform.getLog(context.bundle)) }
  }

  /**
   * A21: prunes patch backups past their retention window at startup. Only the count is logged —
   * the area holds working-tree paths (A9). Never let start throw.
   */
  private fun sweepPatchBackups() {
    try {
      val removed = PatchQuarantine().sweep()
      if (removed > 0) {
        logger<GitLabEclipseStartup>().info("Removed $removed expired patch backup(s).")
      }
    } catch (e: Exception) {
      logger<GitLabEclipseStartup>().warn("Patch backup sweep skipped: ${e::class.simpleName}")
    }
  }

  /**
   * Restores what the stop hook's UI turn cleared, in one UI turn whose steps each run behind their
   * own boundary ([productionActivationSteps]): a step that throws cannot keep a later registry
   * down.
   *
   * First start may run before the workbench/display exists; each registry's initial state
   * (active=true, epoch=0, empty latest) is already fresh, so skipping the turn is harmless. Never
   * lets start throw.
   */
  internal fun activateGenerationRegistries() {
    runUiTurn("activation", activationSteps)
  }

  companion object {
    /**
     * The stop hook's UI turn, in order. Every CI-lint, discussion and job-log reflect/notify
     * runnable runs on the UI thread and gates on its registry's `active`, so flipping the flags
     * here — not as a bare off-thread write — totally orders the deactivation with each runnable's
     * gate-check-then-act: no runnable can pass its gate and then act after deactivation (#62). The
     * flags go first, so they are down even if a later step throws. If the display is
     * unavailable/disposed the whole turn is skipped and every `active` stays true, which is inert:
     * without a live display no reflect/notify runnable can run (currentDisplay throws
     * IllegalStateException, asyncExec throws SWTException — both caught as no-ops), and the
     * command handlers are unregistered once the bundle stops, so nothing reads `active` after a
     * display-less stop.
     */
    internal fun productionUiShutdownSteps(): List<LifecycleStep> = listOf(
      LifecycleStep("CI lint registry") { CiLintGenerationRegistry.onDeactivate() },
      LifecycleStep("Discussion registry") { DiscussionGenerationRegistry.onDeactivate() },
      LifecycleStep("Job log registry") { JobLogGenerationRegistry.onDeactivate() },
      // Thread popups first, without a copy-text prompt (no dialogs at stop); then the sessions.
      // Editor review sessions gate on the discussion flag; with it down, release their
      // annotations, ruler and part listeners in this same UI turn (FR-11). Never throws.
      LifecycleStep("MR thread popups") { MrThreadPopups.discardAll() },
      // Design §17 step 1: the Quick Chat popups too, before stop() cancels their scope. Never throws.
      LifecycleStep("Quick Chat popups") { QuickChatPopups.discardAll() },
      LifecycleStep("Review sessions") { ReviewSessionRegistry.clear() },
      // Close in-memory trace / merged-YAML editors and remove their listeners. Each is a no-op
      // internally if the workbench is closing (editors die with it).
      LifecycleStep("Job log editors") { JobLogEditorOpener.disposeAtShutdown() },
      LifecycleStep("Merged YAML editors") { MergedYamlEditorOpener.disposeAtShutdown() },
      // MR file diffs (REVIEW-01): flag down first, then cancel the diff Job family, then close the
      // diff editors unless the workbench is closing. Contained inside; never throws.
      LifecycleStep("MR diff editors") { MrFileDiffEntryPoint.onStop() },
      // Forget the open Agent Platform views: a readiness callback after stop reloads nothing.
      LifecycleStep("Agent Platform views") { AgentPlatformViews.onDeactivate() },
      // I6: take back the lifecycle listeners (the store, closed before this turn, already dropped
      // them) and the refresher's window, part, theme, auth and preference listeners — the window and
      // part ones belong to the UI thread.
      LifecycleStep("Lifecycle listeners") { LifecycleListenerRegistrations.closeAll() },
      LifecycleStep("Duo status refresher") { service<DuoStatusRefresher>().dispose() },
      // Status bar §4.1/§5: every window's current-branch refresher, listeners and fetch; late completions on the
      // shared IO scope are inert after this.
      LifecycleStep("Current-branch state") { currentBranchStateOrNull()?.dispose() },
    )

    /**
     * The listeners the start hook attaches to the lifecycle store, before the language server
     * starts: the failure popup (LS-02), the Duo status refresh (QCHAT-07) and the end of the
     * sessions a transition leaves behind (LS-08). Each resolved lazily, behind its own boundary.
     */
    internal fun productionLifecycleListeners(): List<Pair<String, () -> LanguageServerLifecycleListener>> = listOf(
      "Language server failure notifier" to { service<LanguageServerFailureNotifier>() },
      "Duo status refresher" to { service<DuoStatusRefresher>() },
      "Language server messages" to { service<LanguageServerMessagePresenter>() },
    )

    /**
     * Posted to the UI thread once the language server was started: the refresher's window and part
     * listeners must be added there, and one explicit refresh replaces the static trim icon, which
     * `HandlerProxy.updateElement` does not update before the handler loads (design §14.1 F7). Windows
     * opened later are refreshed by the window listener's windowOpened.
     */
    internal fun productionDuoStatusActivationSteps(): List<LifecycleStep> = listOf(
      LifecycleStep("Duo status refresher") {
        val refresher = service<DuoStatusRefresher>()
        refresher.installWindowListeners()
        refresher.request()
      },
      // Status bar §4.1: an entry (refresher + first fetch) for every open window, and the window listener for
      // later ones. The trim control may have created some entries already (R3); ensureWindow is idempotent.
      LifecycleStep("Current-branch state") { currentBranchStateOrNull()?.activate() },
    )

    /**
     * The start hook's UI turn, in order: the registries first, so a failing [MrThreadPopups.install]
     * cannot keep any of them down. Each `onActivate` invalidates the generations a previous stop
     * left in `latest` (stop lets in-flight work finish) and restores the `active` flag the stop
     * hook cleared — without the job-log one, a stop->start cycle in the same class loader leaves
     * every shouldAct() false forever and "Display Log" silently stops reflecting.
     */
    internal fun productionActivationSteps(): List<LifecycleStep> = listOf(
      LifecycleStep("CI lint registry") { CiLintGenerationRegistry.onActivate() },
      LifecycleStep("Discussion registry") { DiscussionGenerationRegistry.onActivate() },
      LifecycleStep("Job log registry") { JobLogGenerationRegistry.onActivate() },
      LifecycleStep("MR diff registry") { MrFileDiffEntryPoint.onStart() },
      // Wires the review-session hooks (ruler click → popup, close on release, refresh on reload)
      // in the same UI turn the discussion lifecycle comes up.
      LifecycleStep("MR thread popups") { MrThreadPopups.install() },
      LifecycleStep("Agent Platform views") { AgentPlatformViews.onActivate() },
    )
  }
}

/**
 * Attaches the lifecycle listeners, requests the language server start, then hands the Duo status
 * steps to the UI thread.
 *
 * The listeners go first so that the attempt `start()` begins (STARTING, published before it
 * returns) and any later failure, published by the start thread (#124), reach the failure notifier
 * and the status refresher. `start()` does not wait for the install or the spawn. The Duo status
 * steps are posted with [uiDispatch] (`asyncExec`), not a waited UI turn: activation never blocks
 * on the UI thread for them, and a stop that runs first disposes the refresher, whose install is
 * then a no-op. A workbench or display that cannot be reached is the normal early-start case and is
 * logged at INFO by class name. Never throws beyond what `start()` itself documents (it never
 * throws).
 *
 * An extension, not a member, only to keep the plugin class within detekt's function budget.
 */
internal fun GitLabEclipseStartup.startLanguageServer(bundle: Bundle) {
  bestEffort("Lifecycle listeners", "activation") {
    LifecycleListenerRegistrations.attach(service<LanguageServerLifecycleStore>(), lifecycleListeners)
  }
  service<GitLabLanguageServerProcessProvider>().start(bundle)
  val steps = duoStatusActivationSteps
  try {
    uiDispatch(Runnable { steps.forEach { step -> bestEffort(step.name, "activation", step.run) } })
  } catch (e: IllegalStateException) {
    logUiTurnSkipped("activation", e, normalExit = true)
  } catch (e: SWTException) {
    logUiTurnSkipped("activation", e, normalExit = e.code == SWT.ERROR_DEVICE_DISPOSED)
  } catch (e: Exception) {
    logUiTurnSkipped("activation", e, normalExit = false)
  }
}

/**
 * The current-branch registry if Koin is up, else null, so its activation and shutdown steps are no-ops on a
 * start whose Koin never came up (or a test container that does not declare it).
 */
private fun currentBranchStateOrNull(): CurrentBranchStateService? =
  GlobalContext.getOrNull()?.getOrNull<CurrentBranchStateService>()
