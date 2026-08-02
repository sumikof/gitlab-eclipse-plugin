package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.CiLintService
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.model.CiLintResult
import com.gitlab.eclipse.ci.lint.ActiveEditorContent
import com.gitlab.eclipse.ci.lint.CiLintGenerationRegistry
import com.gitlab.eclipse.ci.lint.CiLintKey
import com.gitlab.eclipse.ci.lint.MergedYamlEditorOpener
import com.gitlab.eclipse.ci.lint.MergedYamlKey
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.mergerequests.RepositoryContextResolver
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CoroutineScope
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.commands.ExecutionException
import org.eclipse.core.commands.NotEnabledException
import org.eclipse.core.commands.NotHandledException
import org.eclipse.core.commands.common.NotDefinedException
import org.eclipse.jface.dialogs.MessageDialog
import org.eclipse.swt.SWT
import org.eclipse.swt.widgets.Shell
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.handlers.HandlerUtil
import org.eclipse.ui.handlers.IHandlerService

/**
 * Shows the merged (expanded) GitLab CI configuration for the active editor's content (design
 * §8.6). Same execute skeleton as [ValidateCiConfigHandler] — editor snapshot + activation epoch
 * captured in the execute UI turn, interactive repo resolution, pre-numbering epoch guard,
 * UI-thread generation assignment, then [launchCiLint] — differing only in the result callback:
 * a merged yaml opens/reloads the in-memory read-only editor ([MergedYamlEditorOpener]); an
 * unmergeable configuration raises an error dialog offering to run the validate command.
 */
@Suppress("unused")
class ShowMergedCiConfigHandler(
  private val contextResolver: RepositoryContextResolver = RepositoryContextResolver(),
  private val service: CiLintService = CiLintService(),
) : AbstractHandler() {
  private val log = logger<ShowMergedCiConfigHandler>()
  private val apiClient by lazyService<GitLabApiClient>()
  private val coroutineScope by lazyService<CoroutineScope>()

  override fun execute(event: ExecutionEvent): Any? {
    // UI thread: capture the active editor's text + source identity and the activation epoch
    // in this same UI turn, so the pair can never mix two editors (§8.7).
    val active = ActiveEditorContent.of(event)
    if (active == null) {
      NotificationUtils.show(NO_OPEN_FILE_MESSAGE)
      return null
    }
    // For the merge-unavailable error dialog; null is fine (MessageDialog accepts a null shell).
    val shell = HandlerUtil.getActiveShell(event)
    val startEpoch = CiLintGenerationRegistry.currentEpoch
    contextResolver.selectActiveContext { ctx ->
      // UI thread. Pre-numbering guard FIRST (§8.6): a stopped activation, or a stop→start
      // cycle since execute, must neither number a generation nor launch a lint.
      if (!CiLintGenerationRegistry.active || CiLintGenerationRegistry.currentEpoch != startEpoch) {
        return@selectActiveContext
      }
      if (ctx == null) return@selectActiveContext // resolver already notified the user
      val key = CiLintKey(
        command = "showMergedCiConfig",
        instanceUrl = normalizeInstanceUrl(ctx.instanceUrl),
        projectId = ctx.projectId,
        sourceId = active.sourceId,
      )
      // UI thread: number this run as the latest for the key, superseding in-flight priors.
      val myGen = CiLintGenerationRegistry.nextGeneration(key)
      launchCiLint(coroutineScope, log, apiClient, service, ctx, active.text, key, myGen) { result ->
        onShowMergedLinted(result, key, active.sourceId, shell)
      }
    }
    return null
  }

  /**
   * UI thread, latest-generation gate already passed: open/reload the merged-YAML editor, or —
   * when the configuration cannot be merged — audit the lint errors and offer the validate
   * command via an error dialog. [MergedYamlEditorOpener.openOrReload]'s PartInitException/
   * CoreException intentionally propagate: [launchCiLint]'s reflect wrapper catches them
   * in-UI (Error Log + latest-gated generic notification).
   */
  private fun onShowMergedLinted(result: CiLintResult, key: CiLintKey, sourceId: String, shell: Shell?) {
    val merged = result.mergedYaml
    if (merged != null) {
      MergedYamlEditorOpener.openOrReload(MergedYamlKey(key.instanceUrl, key.projectId, sourceId), merged)
    } else {
      // Lint error display lines (never a response body) go to the Error Log for diagnosis.
      result.errors.forEach { log.error("ciLint mergedUnavailable error=$it") }
      val pressed = MessageDialog.open(
        MessageDialog.ERROR,
        shell,
        "GitLab",
        CANNOT_MERGE_MESSAGE,
        SWT.NONE,
        "Validate GitLab CI Config",
        "Close",
      )
      // The custom-label overload returns the pressed button index: 0 = the first button =
      // "Validate GitLab CI Config" (ESC/close returns a non-zero code and does nothing).
      if (pressed == 0) executeValidateCommand()
    }
  }

  /**
   * UI thread: dispatch the validate command through the workbench handler service — the same
   * path as a menu invocation, independent of the shared coroutine scope. Each checked failure
   * mode is caught and audited individually; none may escape into the UI turn.
   */
  private fun executeValidateCommand() {
    val handlerService = PlatformUI.getWorkbench().getService(IHandlerService::class.java)
    if (handlerService == null) {
      log.error("IHandlerService is unavailable; cannot run $VALIDATE_CI_CONFIG_COMMAND_ID.")
      return
    }
    try {
      handlerService.executeCommand(VALIDATE_CI_CONFIG_COMMAND_ID, null)
    } catch (e: ExecutionException) {
      log.error(VALIDATE_DISPATCH_FAILED, e)
    } catch (e: NotDefinedException) {
      log.error(VALIDATE_DISPATCH_FAILED, e)
    } catch (e: NotEnabledException) {
      log.error(VALIDATE_DISPATCH_FAILED, e)
    } catch (e: NotHandledException) {
      log.error(VALIDATE_DISPATCH_FAILED, e)
    }
  }

  private companion object {
    const val VALIDATE_DISPATCH_FAILED =
      "Failed to execute $VALIDATE_CI_CONFIG_COMMAND_ID from the merge-unavailable dialog."
  }
}
