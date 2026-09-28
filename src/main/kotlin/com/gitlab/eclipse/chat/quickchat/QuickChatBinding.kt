package com.gitlab.eclipse.chat.quickchat

/**
 * Which project a preflight judged (design §9.2.2 step 4). Two sends may share a preflight only
 * when their keys are equal; a different key means a different project, hence a new conversation.
 */
data class ProjectKey(val kind: Kind, val instanceUrl: String?, val fullPath: String?) {
  enum class Kind { RESOLVED, NOT_IN_REPOSITORY, NO_GITLAB_REMOTE }

  companion object {
    val NOT_IN_REPOSITORY = ProjectKey(Kind.NOT_IN_REPOSITORY, null, null)
    val NO_GITLAB_REMOTE = ProjectKey(Kind.NO_GITLAB_REMOTE, null, null)

    /** [instanceUrl] normalized, [fullPath] decoded (the form sent to the project query). */
    fun resolved(instanceUrl: String, fullPath: String) = ProjectKey(Kind.RESOLVED, instanceUrl, fullPath)
  }
}

/**
 * A passed preflight (design §9.2.2): the version was acceptable and, for a project file, the
 * project was found with Duo not turned off. [resourceId] is the project's GraphQL id, or null for
 * a file outside any project.
 */
data class Preflight(val resourceId: String?, val projectKey: ProjectKey)

/**
 * The immutable copy of a conversation's binding handed to the background (design §9.2.3, §12.1).
 * [instanceUrl] is normalized; [threadId] is null until GitLab has returned one.
 */
data class ConversationBinding(val instanceUrl: String, val preflight: Preflight, val threadId: String?)

/**
 * What the conversation's binding becomes after a send (design §12.3): a full replacement, not a
 * delta — [threadId] is already null when the project changed or no thread exists yet.
 * [projectChanged] asks the UI to insert the "New chat" separator before this send's question (A26).
 */
data class BindingUpdate(
  val instanceUrl: String,
  val preflight: Preflight,
  val threadId: String?,
  val projectChanged: Boolean,
) {
  /** The binding to store in the conversation. */
  fun toBinding(): ConversationBinding = ConversationBinding(instanceUrl, preflight, threadId)
}
