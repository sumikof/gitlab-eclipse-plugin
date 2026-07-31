package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.PipelineActionService
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.mergerequests.CurrentBranchGitReader
import com.gitlab.eclipse.mergerequests.EffectiveRef
import com.gitlab.eclipse.mergerequests.RepositoryContext
import com.gitlab.eclipse.mergerequests.RepositoryContextResolver
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.jface.dialogs.MessageDialog
import org.eclipse.swt.widgets.Display
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.handlers.HandlerUtil
import java.io.File

/**
 * Creates a new pipeline for the current branch's effective ref (design §8.3/§9). Thin SWT
 * shell over the SWT-free [runCreatePipeline] core. Entry point = the GitLab sidebar toolbar
 * (no pipeline node required). Flow: interactive repo resolution (UI) → branch read + ref
 * resolution (IO) → confirm dialog (UI) → in-flight guard (UI) → capture + same-instance gate
 * + POST (IO) → refresh originating window on success (UI). The connection is captured OFF the
 * UI thread ([GitLabApiClient.captureConnection] may block on an OAuth refresh). Nothing escapes
 * the shared IO scope: CancellationException is rethrown, any other throwable is caught + audited.
 */
@Suppress("unused")
class CreatePipelineHandler(
  private val contextResolver: RepositoryContextResolver = RepositoryContextResolver(),
  private val branchReader: CurrentBranchGitReader = CurrentBranchGitReader(),
  private val actionService: PipelineActionService = PipelineActionService(),
) : AbstractHandler() {
  private val logger = logger<CreatePipelineHandler>()
  private val apiClient by lazyService<GitLabApiClient>()
  private val coroutineScope by lazyService<CoroutineScope>()

  override fun execute(event: ExecutionEvent): Any? {
    val window = HandlerUtil.getActiveWorkbenchWindow(event)
    // Interactive: multi-repo → picker, none → resolver notifies. Callback runs on the UI thread.
    contextResolver.selectActiveContext { context ->
      if (context != null) onContextResolved(context, window)
    }
    return null
  }

  /** UI thread: repo resolved. Read the branch OFF the UI thread, then confirm + create. */
  private fun onContextResolved(context: RepositoryContext, window: IWorkbenchWindow?) {
    coroutineScope.launch {
      try {
        val branch = branchReader.read(File(context.gitDir))
        val ref = EffectiveRef.resolve(branch, context.remoteName)
        Display.getDefault().asyncExec { confirmAndCreate(context, ref, window) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.error("Failed to read the current branch for create pipeline.", e)
        Display.getDefault().asyncExec { NotificationUtils.show(CANNOT_DETERMINE_BRANCH) }
      }
    }
  }

  /** UI thread: null ref → notify; else confirm, then acquire the guard and launch the create. */
  private fun confirmAndCreate(context: RepositoryContext, ref: String?, window: IWorkbenchWindow?) {
    if (ref == null) {
      NotificationUtils.show(CANNOT_DETERMINE_BRANCH)
      return
    }
    val confirmed = MessageDialog.openConfirm(
      window?.shell,
      "Create pipeline",
      "Create a new pipeline for '$ref' in ${context.webUrl}?",
    )
    if (!confirmed) return

    val key = CreateWriteKey(normalizeInstanceUrl(context.instanceUrl), context.projectId, ref)
    if (!InFlightWriteGuard.tryAcquire(key)) {
      logger.info("A create for '$ref' in ${context.projectId} is already in flight; ignoring.")
      return
    }
    launchCreate(context, ref, key, window)
  }

  /** Runs capture + gate + POST off the UI thread; releases the guard on every exit path. */
  private fun launchCreate(
    context: RepositoryContext,
    ref: String,
    key: CreateWriteKey,
    window: IWorkbenchWindow?,
  ) {
    coroutineScope.launch {
      try {
        val result = runCreatePipeline(
          contextInstanceUrl = context.instanceUrl,
          capture = { apiClient.captureConnection() },
          create = { conn -> actionService.create(conn, context.projectId, ref) },
        )
        val audit = buildCreateAuditMessage(context.instanceUrl, context.projectId, ref, result)
        when (result) {
          is CreateResult.Created -> {
            logger.info(audit)
            Display.getDefault().asyncExec {
              findSidebarViewIn(window)?.refresh()
              NotificationUtils.show("Pipeline created.")
            }
          }
          is CreateResult.Failed -> {
            logger.error(audit)
            Display.getDefault().asyncExec { NotificationUtils.show(CREATE_FAILED) }
          }
          CreateResult.InstanceMismatch, CreateResult.ConnectionUnstable -> {
            logger.warn(audit)
            Display.getDefault().asyncExec { NotificationUtils.show(CONNECTION_CHANGED_MESSAGE) }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        logger.error(
          buildCreateAuditMessage(
            context.instanceUrl,
            context.projectId,
            ref,
            CreateResult.Failed(
              WriteOutcome.Failure(
                httpStatus = null,
                correlationId = null,
                failureKind = "unexpected",
              ),
            ),
          ),
          e,
        )
        Display.getDefault().asyncExec { NotificationUtils.show(CREATE_FAILED) }
      } finally {
        InFlightWriteGuard.release(key)
      }
    }
  }

  private companion object {
    const val CANNOT_DETERMINE_BRANCH =
      "Cannot determine the current branch (it may be a detached HEAD or the repository could " +
        "not be read). See the Error Log for details."
    const val CREATE_FAILED = "The action failed. Refresh the sidebar to check the current state."
  }
}
