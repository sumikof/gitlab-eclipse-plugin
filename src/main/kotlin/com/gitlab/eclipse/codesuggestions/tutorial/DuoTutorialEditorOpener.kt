package com.gitlab.eclipse.codesuggestions.tutorial

import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.openInActiveEditor
import org.eclipse.core.resources.IFile
import org.eclipse.core.runtime.jobs.ISchedulingRule
import org.eclipse.jface.operation.IRunnableWithProgress
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.lang.reflect.InvocationTargetException

/**
 * Opens the Tutorial file the writer handed back as [WriterOutcome.Ready] (design §9.2 "OpenOnUi").
 *
 * Two hops, on purpose. First a plain `asyncExec` — **no rule in hand** — to reach the UI thread.
 * Then, on the UI thread, `IProgressService.runInUI(window, runnable, root)`, which takes the root
 * rule with a monitor that keeps running the event loop while it waits. That is what rules out the
 * two deadlocks the design names: a rule-holding `UIJob` waiting for the UI from a worker thread,
 * and a bare `IWorkspace.run` / `beginRule` on the UI thread while another job holds the rule and
 * `syncExec`s into the UI. Under the rule, ownership is verified **again** right before the open:
 * between `Ready` and this turn a queued workspace job could have deleted the project and made a
 * same-named one, and the [IFile] handle would then name a stranger's file.
 *
 * The Code Suggestions state is read **after** the editor opened (R7, round 24): the notice is
 * about the editor the user is looking at, not about the moment the command was clicked.
 *
 * Every UI and platform touch is a constructor seam with a production default; the class itself is
 * thread-agnostic and headless-testable.
 */
class DuoTutorialEditorOpener(
  private val onUiThread: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
  private val runInUI: (IWorkbenchWindow, IRunnableWithProgress, ISchedulingRule) -> Unit =
    ::runInUIWithProgressService,
  private val ownership: DuoTutorialOwnership = DuoTutorialOwnership(),
  private val openEditor: (IFile) -> Boolean = ::openTutorialInActiveEditor,
  private val localCodeSuggestionsEnabled: () -> Boolean = {
    service<ScopedPreferenceStore>().getBoolean(PreferenceConstants.CODE_SUGGESTIONS_ENABLED)
  },
  private val firstEngagedCheckId: () -> String? = {
    service<CodeSuggestionsStateService>().getFirstEngagedCheck()?.checkId
  },
  private val notify: (String) -> Unit = { NotificationUtils.show(it) },
) {
  private val logger by lazy { logger<DuoTutorialEditorOpener>() }

  /** Any thread (the job's `done` callback in production). Never throws. */
  fun open(window: IWorkbenchWindow?, file: IFile) {
    try {
      onUiThread(Runnable { openOnUiThread(window, file) })
    } catch (e: Exception) {
      // Display gone (SWTException) or workbench torn down (IllegalStateException): nothing to open into.
      logger.warn("$LOG_PREFIX could not reach the UI thread: ${e.javaClass.name}")
    }
  }

  /**
   * UI thread. The whole `runInUI` call is contained (round 22 P2): [InterruptedException] is the
   * user cancelling the rule wait — the Tutorial exists, a re-run opens it, so no notice — and
   * anything else, [InvocationTargetException] wrapping what the runnable threw included, is a
   * failure notice plus the class name. Nothing escapes into the event loop.
   */
  private fun openOnUiThread(window: IWorkbenchWindow?, file: IFile) {
    if (window == null) {
      logger.error("$LOG_PREFIX no workbench window to open in")
      notify(DuoTutorialMessages.OPEN_FAILED)
      return
    }
    try {
      runInUI(window, IRunnableWithProgress { verifyAndOpen(file) }, file.workspace.root)
    } catch (e: InterruptedException) {
      logger.info("$LOG_PREFIX cancelled while waiting for the workspace: ${e.javaClass.name}")
    } catch (e: Exception) {
      val cause = (e as? InvocationTargetException)?.cause ?: e
      logger.error("$LOG_PREFIX could not open the tutorial: ${cause.javaClass.name}")
      notify(DuoTutorialMessages.OPEN_FAILED)
    }
  }

  /** Inside `runInUI`, holding the root rule. Exceptions propagate to [openOnUiThread]'s catch. */
  private fun verifyAndOpen(file: IFile) {
    val project = file.project
    val refusal = when {
      !project.exists() -> DuoTutorialMessages.TUTORIAL_CHANGED
      !project.isOpen -> DuoTutorialMessages.PROJECT_CLOSED
      !ownership.isOwned(project) -> DuoTutorialMessages.NOT_OWNED
      !file.exists() -> DuoTutorialMessages.TUTORIAL_CHANGED
      else -> null
    }
    if (refusal != null) {
      logger.info("$LOG_PREFIX ownership did not hold at open time; not opening")
      notify(refusal)
      return
    }
    if (!openEditor(file)) {
      logger.error("$LOG_PREFIX no active workbench page")
      notify(DuoTutorialMessages.OPEN_FAILED)
      return
    }
    DuoTutorialMessages.codeSuggestionsNotice(localCodeSuggestionsEnabled(), firstEngagedCheckId())?.let(notify)
  }

  private companion object {
    const val LOG_PREFIX = "GitLab Duo Tutorial:"
  }
}

/** Production default: the workbench progress service, with [window] as the runnable context. */
private fun runInUIWithProgressService(
  window: IWorkbenchWindow,
  runnable: IRunnableWithProgress,
  rule: ISchedulingRule,
) {
  window.workbench.progressService.runInUI(window, runnable, rule)
}

/** Production default. UI thread only; false when there is no active page. */
private fun openTutorialInActiveEditor(file: IFile): Boolean =
  openInActiveEditor(workspaceFile = { file }, fileStore = { null })
