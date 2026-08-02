package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.CiLintService
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.model.CiLintResult
import com.gitlab.eclipse.ci.lint.ActiveEditorContent
import com.gitlab.eclipse.ci.lint.CiLintGenerationRegistry
import com.gitlab.eclipse.ci.lint.CiLintKey
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.mergerequests.RepositoryContextResolver
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CoroutineScope
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent

// User-facing CI lint messages + the validate command id, shared by both lint handlers
// (design §8.6); defined once here, referenced from ShowMergedCiConfigHandler.
internal const val NO_OPEN_FILE_MESSAGE = "GitLab: No open file."
internal const val CI_CONFIG_VALID_MESSAGE = "GitLab: Your CI configuration is valid."
internal const val CI_CONFIG_INVALID_MESSAGE = "GitLab: Invalid CI configuration."
internal const val CANNOT_MERGE_MESSAGE =
  "GitLab: Cannot merge the CI configuration. Check your CI configuration files for errors."
internal const val VALIDATE_CI_CONFIG_COMMAND_ID = "com.gitlab.eclipse.commands.ValidateCiConfig"

/**
 * Validates the active editor's content as a GitLab CI configuration (design §8.6). Thin SWT
 * shell over the SWT-free [launchCiLint] skeleton: capture the editor snapshot + activation
 * epoch in the execute UI turn, resolve the repository interactively, then — still on the UI
 * thread and only if the activation epoch is unchanged — number the run and launch the lint.
 * The result callback runs on the UI thread behind [CiLintGenerationRegistry]'s latest gate.
 */
@Suppress("unused")
class ValidateCiConfigHandler(
  private val contextResolver: RepositoryContextResolver = RepositoryContextResolver(),
  private val service: CiLintService = CiLintService(),
) : AbstractHandler() {
  private val log = logger<ValidateCiConfigHandler>()
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
    val startEpoch = CiLintGenerationRegistry.currentEpoch
    contextResolver.selectActiveContext { ctx ->
      // UI thread. Pre-numbering guard FIRST (§8.6): a stopped activation, or a stop→start
      // cycle since execute, must neither number a generation nor launch a lint.
      if (!CiLintGenerationRegistry.active || CiLintGenerationRegistry.currentEpoch != startEpoch) {
        return@selectActiveContext
      }
      if (ctx == null) return@selectActiveContext // resolver already notified the user
      val key = CiLintKey(
        command = "validateCiConfig",
        instanceUrl = normalizeInstanceUrl(ctx.instanceUrl),
        projectId = ctx.projectId,
        sourceId = active.sourceId,
      )
      // UI thread: number this run as the latest for the key, superseding in-flight priors.
      val myGen = CiLintGenerationRegistry.nextGeneration(key)
      launchCiLint(coroutineScope, log, apiClient, service, ctx, active.text, key, myGen) { result ->
        onValidateLinted(result)
      }
    }
    return null
  }

  /** UI thread, latest-generation gate already passed: map the lint result to notifications. */
  private fun onValidateLinted(result: CiLintResult) {
    if (result.valid) {
      NotificationUtils.showOnUiThread(CI_CONFIG_VALID_MESSAGE)
    } else {
      // No severity in NotificationUtils — the wording carries it. The first lint error (a
      // display line, not a response body) rides along in its own popup when present.
      NotificationUtils.showOnUiThread(CI_CONFIG_INVALID_MESSAGE)
      result.errors.firstOrNull()?.let { NotificationUtils.showOnUiThread(it) }
    }
  }
}
