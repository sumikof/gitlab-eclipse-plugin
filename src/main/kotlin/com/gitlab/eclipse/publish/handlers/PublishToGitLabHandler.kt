package com.gitlab.eclipse.publish.handlers

import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.navigation.WorkspaceProjectPicker
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.publish.Preflight
import com.gitlab.eclipse.publish.PublishIntentRecovery
import com.gitlab.eclipse.publish.PublishMessages
import com.gitlab.eclipse.publish.PublishOutcome
import com.gitlab.eclipse.publish.PublishPreflight
import com.gitlab.eclipse.publish.PublishRecord
import com.gitlab.eclipse.publish.PublishRecordStore
import com.gitlab.eclipse.publish.PublishRequest
import com.gitlab.eclipse.publish.PublishToGitLabService
import com.gitlab.eclipse.publish.Recovery
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.io.File

/**
 * `gl.publishToGitLab`: publishes a workspace folder as a new GitLab project (design F6).
 *
 * Every destructive step sits behind a confirmation, which is what makes A15 hold: the preflight
 * only reads, the dialogs change nothing, and `git init` does not run until the user has seen the
 * file count and the ignore-rules warning.
 */
class PublishToGitLabHandler : AbstractHandler() {
  private val logger by lazy { logger<PublishToGitLabHandler>() }
  private val coroutineScope by lazyService<CoroutineScope>()
  private val dialogs = PublishDialogs()

  override fun execute(event: ExecutionEvent) {
    val folders = WorkspaceProjectPicker.workspaceRepoDirs()
    if (folders.isEmpty()) {
      dialogs.notify("GitLab: There is no project in the workspace to publish.")
      return
    }
    val folder = if (folders.size == 1) folders.first() else dialogs.pickFolder(folders) ?: return
    val instanceUrl = service<ScopedPreferenceStore>().getString(PreferenceConstants.GITLAB_INSTANCE_URL)
    if (instanceUrl.isNullOrBlank()) {
      dialogs.notify("GitLab: Set your GitLab instance URL in the GitLab preferences first.")
      return
    }

    coroutineScope.launch {
      try {
        val preflight = PublishPreflight().inspect(folder, instanceUrl)
        currentDisplay.asyncExec { route(preflight, folder, instanceUrl) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Shared plain-Job scope: an escape cancels every other coroutine on it (issue #16).
        logger.error("Publish preflight failed: ${e.javaClass.name}")
        uiNotify("GitLab: Could not inspect that folder. See the Error Log.")
      }
    }
  }

  /** Runs on the UI thread: every branch either asks something or reports something. */
  private fun route(preflight: Preflight, folder: File, instanceUrl: String) {
    when (preflight) {
      is Preflight.Blocked -> dialogs.notify(PublishMessages.of(preflight.reason))
      is Preflight.Fresh -> startFresh(preflight, folder, instanceUrl)
      is Preflight.ResumePush ->
        if (dialogs.confirm(RESUME_QUESTION)) {
          runInBackground { PublishToGitLabService().resumePush(preflight.state, folder, instanceUrl) }
        }
      is Preflight.RecoverIntent -> recover(preflight.intent, folder, instanceUrl)
    }
  }

  private fun startFresh(fresh: Preflight.Fresh, folder: File, instanceUrl: String) {
    val namespace = dialogs.promptNamespace() ?: return
    val name = dialogs.promptProjectName() ?: return
    val visibility = dialogs.pickVisibility() ?: return
    val useSsh = dialogs.pickConnection() ?: return
    // The last point at which nothing has changed on disk or on the server (A15).
    if (!dialogs.confirm(confirmationText(fresh, name))) return

    runInBackground {
      PublishToGitLabService().publish(
        PublishRequest(folder, namespace.trim(), name.trim(), visibility, useSsh),
        instanceUrl,
      )
    }
  }

  /**
   * §9.6 recovery. The design's "the previous POST failed outright, so drop the intent" shortcut is
   * NOT implemented: the previous attempt's result is not persisted, so it cannot be told apart
   * from a lost response. Everything therefore goes through the lookup and, unless ownership is
   * proven, an explicit question — the safe half of the design's own rule.
   */
  private fun recover(intent: PublishRecord.Intent, folder: File, instanceUrl: String) {
    coroutineScope.launch {
      try {
        when (val decision = PublishIntentRecovery().decide(intent, DEFAULT_REMOTE_NAME)) {
          Recovery.Discard -> {
            PublishRecordStore().remove(intent.repositoryRootPath)
            uiNotify("GitLab: The previous attempt created nothing. Run the command again to publish.")
          }
          is Recovery.Adopt -> {
            val outcome = PublishToGitLabService().resumePush(decision.state, folder, instanceUrl)
            uiNotify(PublishMessages.of(outcome))
          }
          is Recovery.Confirm -> currentDisplay.asyncExec {
            confirmAdoption(decision, intent, folder, instanceUrl)
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.error("Publish recovery failed: ${e.javaClass.name}")
        uiNotify("GitLab: Could not recover the previous attempt. See the Error Log.")
      }
    }
  }

  private fun confirmAdoption(
    decision: Recovery.Confirm,
    intent: PublishRecord.Intent,
    folder: File,
    instanceUrl: String,
  ) {
    val question = "A project already exists at ${decision.webUrl}. Was it created by the previous " +
      "run of this command? Only continue if it is yours to push to."
    if (!dialogs.confirm(question)) {
      PublishRecordStore().remove(intent.repositoryRootPath)
      dialogs.notify("GitLab: Left the existing project alone. Run the command again to publish elsewhere.")
      return
    }
    runInBackground { PublishToGitLabService().resumePush(decision.state, folder, instanceUrl) }
  }

  private fun confirmationText(fresh: Preflight.Fresh, name: String): String = buildString {
    if (!fresh.isRepository) {
      append("This folder is not a git repository yet. ")
      append("It will be initialised and up to ${fresh.trackableFileCount} file(s) committed. ")
    }
    if (!fresh.hasGitignore) {
      // Secrets protection (§9.6 phase 1): with no ignore rules, everything in the folder goes up.
      append("There is no .gitignore, so every file in the folder will be published. ")
    }
    append("Create the project \"$name\" on GitLab and push to it?")
  }

  private fun runInBackground(work: () -> PublishOutcome) {
    coroutineScope.launch {
      try {
        uiNotify(PublishMessages.of(work()))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.error("Publishing failed: ${e.javaClass.name}")
        uiNotify("GitLab: Could not publish. See the Error Log.")
      }
    }
  }

  private fun uiNotify(message: String) = currentDisplay.asyncExec { dialogs.notify(message) }

  private companion object {
    const val DEFAULT_REMOTE_NAME = "origin"
    const val RESUME_QUESTION =
      "The project was created last time but the push did not finish. Retry the push now?"
  }
}
