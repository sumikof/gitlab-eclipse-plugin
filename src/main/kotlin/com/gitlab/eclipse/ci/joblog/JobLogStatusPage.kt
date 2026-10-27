package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.JobActionService
import com.gitlab.eclipse.ci.CiAction
import com.gitlab.eclipse.ci.CiStatus
import com.gitlab.eclipse.ci.actions.InFlightWriteGuard
import com.gitlab.eclipse.ci.actions.JobWriteTarget
import com.gitlab.eclipse.ci.actions.WriteKey
import com.gitlab.eclipse.ci.actions.WriteOutcome
import com.gitlab.eclipse.ci.actions.jobWriteKeyOf
import com.gitlab.eclipse.ci.actions.launchCiWrite
import com.gitlab.eclipse.ci.actions.refreshAfterCiWrite
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CoroutineScope
import org.eclipse.jface.resource.JFaceResources
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTException
import org.eclipse.swt.layout.GridData
import org.eclipse.swt.layout.GridLayout
import org.eclipse.swt.widgets.Button
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Control
import org.eclipse.swt.widgets.Label
import org.eclipse.ui.PlatformUI
import java.time.format.DateTimeFormatter

private const val PAGE_MARGIN = 20
private const val PAGE_SPACING = 10
private val LAST_CHECKED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/** The write a waiting page offers (VS Code `pending_job_webview_controller.ts:15-19`). */
enum class PageButton(val label: String, val action: String) {
  PLAY("Trigger this manual action", "play"),
  CANCEL("Cancel this job", "cancel"),
  RETRY("Retry this job", "retry"),
}

/** What a non-log page shows. [lastChecked] only while the job is still waiting. */
data class StatusPageModel(
  val title: String,
  val description: String,
  val lastChecked: String?,
  val button: PageButton?,
)

private class WaitingText(val title: String, val description: String, val button: PageButton?)

// VS Code `ci_status_metadata.ts` illustrations of the statuses a job waits in.
private val WAITING = mapOf(
  "manual" to WaitingText(
    "This job requires a manual action",
    "This job requires manual intervention to start.",
    PageButton.PLAY,
  ),
  "created" to WaitingText(
    "This job has not been triggered yet",
    "This job depends on upstream jobs that need to succeed in order for this job to be triggered",
    PageButton.CANCEL,
  ),
  // VS Code has no illustration here and falls back to "no trace"; the status name says more (L-B2-8).
  "waiting_for_resource" to WaitingText("Waiting for resource", "", PageButton.CANCEL),
  "preparing" to WaitingText(
    "This job is preparing to start",
    "This job is performing tasks that must complete before it can start",
    PageButton.CANCEL,
  ),
  "pending" to WaitingText(
    "This job has not started yet",
    "This job is in pending state and is waiting to be picked by a runner",
    PageButton.CANCEL,
  ),
  "scheduled" to WaitingText(
    "This is a delayed job",
    "This job will automatically run after its timer finishes. Often they are used for incremental roll-out deploys " +
      "to production environments. When unscheduled it converts into a manual action.",
    PageButton.CANCEL,
  ),
)

// Finished before it started (L-B2-5): only the title, and Retry when the status allows it (L4).
private val FINISHED_TITLES = mapOf(
  "skipped" to "This job has been skipped",
  "canceled" to "This job has been canceled",
)
private const val NO_TRACE_TITLE = "This job does not have a trace."

/**
 * True when a status page of [shownKind], still alive ([pageAlive]), can stay on screen for [next]:
 * the same kind of non-log page, which [JobLogStatusPage.update] changes in place. Then a rebuild
 * the platform asks for (`StatusTextEditor.sanityCheckState` on every activation) is skipped, so a
 * disabled button and a first click survive (B2 review I-1).
 */
internal fun keepsStatusPage(shownKind: Class<out JobLogPage>?, pageAlive: Boolean, next: JobLogPage?): Boolean =
  pageAlive && next != null && next != JobLogPage.Log && next.javaClass == shownKind

/** The model of [page]; null for the log itself. */
fun statusPageModel(page: JobLogPage): StatusPageModel? = when (page) {
  JobLogPage.Log -> null
  JobLogPage.Loading -> StatusPageModel("Loading the job log…", "", null, null)
  JobLogPage.Erased -> StatusPageModel("Job has been erased", "", null, null)
  is JobLogPage.Unavailable -> StatusPageModel(page.message, "", null, null)
  is JobLogPage.Waiting -> {
    val waiting = WAITING[page.status]
    if (waiting == null) {
      val retry = PageButton.RETRY.takeIf { CiStatus.contextAction(page.status) == CiAction.RETRYABLE }
      StatusPageModel(FINISHED_TITLES[page.status] ?: NO_TRACE_TITLE, "", null, retry)
    } else {
      val checked = page.lastChecked?.let { "Last checked at " + LAST_CHECKED_FORMAT.format(it) }
      StatusPageModel(waiting.title, waiting.description, checked, waiting.button)
    }
  }
}

/**
 * [model] as the page shows it: Retry only while the job's session can retry ([JobLogSession.canRetry]),
 * so it goes away while a retry runs and after one succeeded, and comes back after a failed one.
 */
internal fun retryButtonShown(model: StatusPageModel, canRetry: Boolean): StatusPageModel =
  if (model.button == PageButton.RETRY && !canRetry) model.copy(button = null) else model

/**
 * UI thread. Starts the write [launch] makes on [target] unless a write to the same job is in flight
 * (the sidebar's Play / Cancel share the key, [jobWriteKeyOf]); false then, and nothing changes.
 * The button stays disabled until the write is done, on the UI thread, where [onFinished] lets the
 * session check the status at once.
 */
internal fun pressPageButton(
  target: JobWriteTarget,
  launch: (key: WriteKey, onDone: () -> Unit) -> Unit,
  tryAcquire: (WriteKey) -> Boolean = InFlightWriteGuard::tryAcquire,
  release: (WriteKey) -> Unit = InFlightWriteGuard::release,
  setBusy: (Boolean) -> Unit,
  onFinished: () -> Unit,
  runOnUi: (() -> Unit) -> Unit,
): Boolean {
  val key = jobWriteKeyOf(target)
  if (!tryAcquire(key)) return false
  setBusy(true)
  val onDone = {
    runOnUi {
      setBusy(false)
      onFinished()
    }
  }
  var started = false
  try {
    launch(key, onDone)
    started = true
  } finally {
    // A launch that threw never runs its finally: free the job and bring the button back here.
    if (!started) {
      release(key)
      onDone()
    }
  }
  return true
}

/**
 * The audit line of a waiting-page write: the job log's vocabulary ([jobLogAuditMessage]) under the
 * write prefix, so no instance URL, and only the class of an unexpected throwable (R-X-1).
 */
internal fun jobLogWriteAuditMessage(
  action: String,
  projectId: Long,
  jobId: Long,
  outcome: WriteOutcome,
  exceptionClass: String?,
): String = when (outcome) {
  is WriteOutcome.Success ->
    jobLogAuditMessage(
      action,
      projectId,
      jobId,
      "success",
      outcome.result.httpStatus,
      outcome.result.correlationId,
      prefix = JobLogAuditPrefix.WRITE,
    )
  is WriteOutcome.Failure -> {
    val (status, correlationId) = outcome.httpStatus to outcome.correlationId
    val kind = outcome.failureKind
    jobLogAuditMessage(action, projectId, jobId, kind, status, correlationId, exceptionClass, JobLogAuditPrefix.WRITE)
  }
}

/**
 * The SWT page for every non-log page (design §5.3): a title, a description, the time of the last
 * check and at most one button, top to bottom. Built once per kind of page; later changes of the
 * same kind go through [update], which hides what a model leaves out instead of rebuilding.
 * `StatusTextEditor` owns the stacking, the focus and the disposal. UI thread only.
 */
internal class JobLogStatusPage(parent: Composite, model: StatusPageModel, private val onPress: (PageButton) -> Unit) {
  private val root = Composite(parent, SWT.NONE)
  private val title: Label
  private val description: Label
  private val lastChecked: Label
  private val button: Button
  private var shownButton: PageButton? = null
  private var busy = false

  val control: Control
    get() = root

  val isDisposed: Boolean
    get() = root.isDisposed

  init {
    root.layout = GridLayout(1, false).apply {
      marginWidth = PAGE_MARGIN
      marginHeight = PAGE_MARGIN
      verticalSpacing = PAGE_SPACING
    }
    title = Label(root, SWT.WRAP).apply {
      font = JFaceResources.getBannerFont()
      layoutData = fillRow()
    }
    description = Label(root, SWT.WRAP).apply { layoutData = fillRow() }
    lastChecked = Label(root, SWT.NONE).apply { layoutData = GridData(SWT.BEGINNING, SWT.TOP, false, false) }
    button = Button(root, SWT.PUSH).apply {
      layoutData = GridData(SWT.BEGINNING, SWT.TOP, false, false)
      addListener(SWT.Selection) { shownButton?.let(onPress) }
    }
    update(model)
  }

  fun update(model: StatusPageModel) {
    if (root.isDisposed) return
    title.text = model.title
    description.text = model.description
    show(description, model.description.isNotEmpty())
    lastChecked.text = model.lastChecked.orEmpty()
    show(lastChecked, model.lastChecked != null)
    shownButton = model.button
    button.text = model.button?.label.orEmpty()
    button.isEnabled = !busy
    show(button, model.button != null)
    root.layout(true, true)
  }

  /** Disables the button while its write runs. Nothing once the page is gone. */
  fun setBusy(busy: Boolean) {
    this.busy = busy
    if (!button.isDisposed) button.isEnabled = !busy
  }

  private fun fillRow() = GridData(SWT.FILL, SWT.TOP, true, false)

  private fun show(control: Control, visible: Boolean) {
    (control.layoutData as GridData).exclude = !visible
    control.isVisible = visible
  }
}

/**
 * The status page of one [JobLogEditor]: made by `createStatusControl`, updated in place for a
 * page of the same kind, forgotten before `updatePartControl` and on dispose (R2-4), and the
 * button's write. UI thread only.
 */
internal class JobLogStatusPages(private val editor: JobLogEditor) {
  private val scope by lazyService<CoroutineScope>()
  private val apiClient by lazyService<GitLabApiClient>()
  private var current: JobLogStatusPage? = null
  private var currentKind: Class<out JobLogPage>? = null

  /** The page's control, or null for the log (the editor then shows its text). */
  fun create(parent: Composite, page: JobLogPage): Control? {
    val model = shownModel(page) ?: return null
    currentKind = page.javaClass
    return JobLogStatusPage(parent, model, ::press).also { current = it }.control
  }

  /** True when [next] can stay on the page shown now ([keepsStatusPage]). */
  fun keepsShowing(next: JobLogPage?): Boolean = keepsStatusPage(currentKind, current?.isDisposed == false, next)

  // The session re-sends its page whenever its Retry state changes, which brings the button up to date.
  fun update(page: JobLogPage) {
    val model = shownModel(page) ?: return
    current?.takeUnless { it.isDisposed }?.update(model)
  }

  private fun shownModel(page: JobLogPage): StatusPageModel? {
    val model = statusPageModel(page) ?: return null
    val canRetry = editor.jobLogInput?.key?.let(JobLogSessionManager::sessionFor)?.canRetry == true
    return retryButtonShown(model, canRetry)
  }

  fun forget() {
    current = null
    currentKind = null
  }

  private fun press(button: PageButton) {
    val input = editor.jobLogInput ?: return
    val page = current ?: return
    if (button == PageButton.RETRY) {
      // The new job's log replaces this page's editor, as VS Code's panel follows the new job.
      JobLogRetry().press(input, closeOrigin = { editor.site.page.closeEditor(editor, false) }, setBusy = page::setBusy)
      return
    }
    val key = input.key
    val target = JobWriteTarget(key.jobId, key.projectId, input.instanceUrl, input.authFingerprint)
    val window = editor.site.workbenchWindow
    pressPageButton(
      target,
      launch = { writeKey, onDone ->
        launchCiWrite(
          scope,
          logger<JobLogEditor>(),
          writeKey,
          button.action,
          key.projectId,
          apiClient,
          input.instanceUrl,
          input.authFingerprint,
          onSuccess = { refreshAfterCiWrite(window) },
          onDone = onDone,
          auditMessage = { outcome, exceptionClass ->
            jobLogWriteAuditMessage(button.action, key.projectId, key.jobId, outcome, exceptionClass)
          },
          attachThrowable = false,
        ) { connection ->
          val service = JobActionService(apiClient)
          when (button) {
            PageButton.PLAY -> service.play(connection, key.projectId, key.jobId)
            PageButton.CANCEL -> service.cancel(connection, key.projectId, key.jobId)
            // Not reached: Retry went through JobLogRetry above.
            PageButton.RETRY -> service.retry(connection, key.projectId, key.jobId)
          }
        }
      },
      setBusy = page::setBusy,
      onFinished = { JobLogSessionManager.actionDone(key) },
      runOnUi = ::asyncOnUi,
    )
  }
}

// Any thread. Once the workbench or its display is gone the action is dropped (same shape as the
// session's runOnUi).
internal fun asyncOnUi(action: () -> Unit) {
  try {
    val display = PlatformUI.getWorkbench().display
    display.asyncExec {
      try {
        if (!display.isDisposed) action()
      } catch (ignored: SWTException) {
        // Display disposed mid-turn.
      }
    }
  } catch (ignored: SWTException) {
    // Display disposed.
  } catch (ignored: IllegalStateException) {
    // Workbench not running.
  }
}
