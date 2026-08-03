package com.gitlab.eclipse.mergerequests.discussions.actions

import com.gitlab.eclipse.api.DiscussionWriteService
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.ci.actions.selectedSidebarNode
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.mergerequests.discussions.DiscussionGenerationRegistry
import com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteKey
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.views.sidebar.ThreadNode
import com.gitlab.eclipse.views.sidebar.canResolveThread
import com.gitlab.eclipse.views.sidebar.canUnresolveThread
import kotlinx.coroutines.CoroutineScope
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.handlers.HandlerUtil

internal const val RESOLVE_THREAD_COMMAND_ID = "com.gitlab.eclipse.commands.ResolveThread"
internal const val UNRESOLVE_THREAD_COMMAND_ID = "com.gitlab.eclipse.commands.UnresolveThread"

internal const val UNKNOWN_RESOLVE_ACTION_MESSAGE = "Unknown discussion action."

/** Dialog title used for the resolve flow's (text-free) failure notifications. */
private const val RESOLVE_TITLE = "Resolve thread"

/**
 * The target resolution state for a command id, or `null` when the id is not one of this
 * handler's two commands. Pure and top-level so the mapping is unit-testable: getting it
 * backwards would resolve a thread the user asked to unresolve, and `toggleResolve`'s flag is a
 * *target state*, not a toggle.
 */
internal fun resolveTargetStateFor(commandId: String?): Boolean? = when (commandId) {
  RESOLVE_THREAD_COMMAND_ID -> true
  UNRESOLVE_THREAD_COMMAND_ID -> false
  else -> null
}

/**
 * The permission predicate matching a resolve/unresolve request — the same functions the context
 * menu's `visibleWhen` evaluates, picked by the requested target state.
 */
internal fun canApplyResolution(node: ThreadNode, resolved: Boolean): Boolean =
  if (resolved) canResolveThread(node) else canUnresolveThread(node)

/**
 * Resolves or unresolves the selected [ThreadNode] (design §8.5). One class serves both commands,
 * dispatching on `event.command.id` exactly as `PipelineActionHandler` does.
 *
 * There is no text to preserve here, so the body is `""` — the launcher then reports every failure
 * as a plain notification instead of a text-preserving dialog.
 */
@Suppress("unused")
class ThreadResolveHandler : AbstractHandler() {
  private val logger = logger<ThreadResolveHandler>()
  private val apiClient by lazyService<GitLabApiClient>()
  private val coroutineScope by lazyService<CoroutineScope>()
  private val writeService by lazyService<DiscussionWriteService>()

  override fun execute(event: ExecutionEvent): Any? {
    val resolved = resolveTargetStateFor(event.command.id)
    if (resolved == null) {
      NotificationUtils.show(UNKNOWN_RESOLVE_ACTION_MESSAGE)
      return null
    }
    val node = selectedSidebarNode<ThreadNode>(event)
    if (node == null) {
      NotificationUtils.show(SELECT_THREAD_MESSAGE)
      return null
    }
    if (!canApplyResolution(node, resolved)) {
      NotificationUtils.show(NO_PERMISSION_MESSAGE)
      return null
    }
    val window = HandlerUtil.getActiveWorkbenchWindow(event)
    val target = writeTargetOf(node)
    val key = DiscussionWriteKey.forDiscussion(node.sourceInstanceUrl, node.sourceAuthFingerprint, node.replyId)
    val startEpoch = DiscussionGenerationRegistry.currentEpoch
    // The write key omits the action on purpose, so a resolve and an unresolve of the same thread
    // serialize against each other instead of racing.
    discussionWriteLauncher(coroutineScope, logger, window, target, RESOLVE_TITLE)
      .launch(key, "", startEpoch) { _, attemptEpoch ->
        val action = if (resolved) "resolveThread" else "unresolveThread"
        // attemptEpoch, not the captured startEpoch: a [Retry] re-entry re-freezes it, and sending
        // with the stale one would abort the retry with no UI at all.
        auditedDiscussionWrite(apiClient, logger, action, target, key, attemptEpoch) { connection ->
          // `resolved` is a target state, never a flip of the node's current value.
          writeService.toggleResolve(connection, node.replyId, resolved = resolved)
        }
      }
    return null
  }
}
