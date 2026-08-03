package com.gitlab.eclipse.views.sidebar

import org.eclipse.core.expressions.PropertyTester

/**
 * Permission-dependent visibility for the merge-request discussion context-menu actions
 * (design §15.2 / FR-9: never offer an action the account cannot perform). Registered in
 * `plugin.xml` under the `com.gitlab.eclipse.node` namespace for the six properties below;
 * anything else — an unknown property, a receiver of the wrong node type, or a null receiver —
 * is `false`, mirroring [CiActionPropertyTester].
 *
 * Every rule lives in an exported top-level predicate rather than inline here, because the
 * command handlers must re-check the SAME condition at execution time (a menu can be stale, and
 * `visibleWhen` is advisory, not enforcement). Sharing the function is what makes it impossible
 * for the menu and the handler to drift apart; a handler that re-inlined the condition would
 * silently offer an action that then fails server-side.
 */
class DiscussionActionPropertyTester : PropertyTester() {
  override fun test(receiver: Any?, property: String?, args: Array<out Any?>?, expectedValue: Any?): Boolean =
    when (property) {
      "canReplyToThread" -> receiver is ThreadNode && canReplyToThread(receiver)
      "canResolveThread" -> receiver is ThreadNode && canResolveThread(receiver)
      "canUnresolveThread" -> receiver is ThreadNode && canUnresolveThread(receiver)
      "canEditNote" -> receiver is NoteNode && canEditNote(receiver)
      "canDeleteNote" -> receiver is NoteNode && canDeleteNote(receiver)
      "canCommentOnMergeRequest" -> receiver is DiscussionsSectionNode && canCommentOnMergeRequest(receiver)
      else -> false
    }
}

/** Replying creates a note in the thread, so it needs `createNote` on the thread's first note. */
internal fun canReplyToThread(node: ThreadNode): Boolean = node.permissions.createNote

/**
 * Resolving requires a thread that *can* be resolved (a diff-anchored one — GitLab reports
 * `resolvable = false` for overall comments), that is not resolved already, and an account with
 * `resolveNote`.
 */
internal fun canResolveThread(node: ThreadNode): Boolean =
  node.resolvable && !node.resolved && node.permissions.resolveNote

/** The exact mirror of [canResolveThread]: same resolvability and permission, opposite state. */
internal fun canUnresolveThread(node: ThreadNode): Boolean =
  node.resolvable && node.resolved && node.permissions.resolveNote

/** GitLab gates both editing and deleting a note behind the single `adminNote` permission. */
internal fun canEditNote(node: NoteNode): Boolean = node.permissions.adminNote

/** Same permission as [canEditNote] — kept as its own predicate so the two menu rules stay separable. */
internal fun canDeleteNote(node: NoteNode): Boolean = node.permissions.adminNote

/**
 * Commenting on the merge request as a whole uses the section's own flag, which the discussions
 * fetch fills from the merge request's `userPermissions.createNote` — the thread nodes' note-level
 * permission cannot answer it (an MR with no threads yet has no notes to ask).
 */
internal fun canCommentOnMergeRequest(node: DiscussionsSectionNode): Boolean = node.canCreateNote
