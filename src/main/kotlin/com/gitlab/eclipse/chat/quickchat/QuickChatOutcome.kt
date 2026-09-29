package com.gitlab.eclipse.chat.quickchat

/** Why a send failed on the wire (design §14, §16); only this kind, never server text, is logged. */
enum class TransportKind {
  /** The connection could not be established (refused, unresolved host, connect timeout). */
  CONNECT,

  /** A non-2xx HTTP status. */
  HTTP,

  /** A top-level GraphQL `errors` array. */
  GRAPHQL,

  /** A 2xx response that could not be interpreted. */
  INVALID_RESPONSE,

  /** A response did not arrive within the per-request timeout while the deadline had not passed. */
  TIMEOUT,

  /** Any other I/O failure. */
  IO,

  /** The connection settings kept changing while being read. */
  UNSTABLE_CONNECTION,

  /** An exception no other kind describes. */
  UNEXPECTED,
}

/** Why the anchor file's project could not be confirmed (design §9.2.2). Nothing was sent. */
enum class ProjectCheckKind {
  /** The file's project lives on another instance than the connected one. */
  OTHER_INSTANCE,

  /** Resolution failed, or the project path could not be decoded (A25). */
  RESOLUTION_FAILED,

  /** The project query found no project (not visible to this account). */
  PROJECT_NOT_FOUND,
}

/**
 * The single result of one send (design §12.3). [update] is non-null exactly when something must be
 * saved into the conversation's binding: a `threadId`, and/or a passed preflight — the latter even
 * when a later stage failed. The UI applies it in `finishOnce` only.
 */
sealed interface QuickChatOutcome {
  val update: BindingUpdate? get() = null

  data class Answered(val content: String, override val update: BindingUpdate) : QuickChatOutcome

  data class EmptyAnswer(override val update: BindingUpdate) : QuickChatOutcome

  /** `aiAction.errors`, an answer's `errors`, or an `aiAction` without a `requestId` (empty [messages]). */
  data class ServerRejected(val messages: List<String>, override val update: BindingUpdate?) : QuickChatOutcome

  /** The instance is older than 17.10 ([version] as reported), or `aiAction` returned no `threadId`. */
  data class Unsupported(val version: String?, override val update: BindingUpdate? = null) : QuickChatOutcome

  /** Quick Chat is unavailable; [reason] is shown as is, null means the default wording. */
  data class Unavailable(val reason: String?) : QuickChatOutcome {
    companion object {
      /** Design §14: the project's `duoFeaturesEnabled` is false. */
      const val DUO_DISABLED_FOR_PROJECT = "GitLab Duo is turned off for this project."
    }
  }

  data class ProjectCheckFailed(val kind: ProjectCheckKind) : QuickChatOutcome

  /** Produced on the UI thread when the context exceeds its limits; nothing is started. */
  data class TooLarge(val item: TooLargeItem) : QuickChatOutcome

  data class TransportFailed(
    val kind: TransportKind,
    val status: Int?,
    val correlationId: String?,
    override val update: BindingUpdate?,
  ) : QuickChatOutcome

  /** `aiAction` may or may not have reached GitLab (design §16). */
  data class MaybeSent(override val update: BindingUpdate?) : QuickChatOutcome

  /** The deadline passed; [beforeSend] guarantees `aiAction` was not and will not be sent (§12.4). */
  data class TimedOut(val beforeSend: Boolean, override val update: BindingUpdate?) : QuickChatOutcome

  /** Too many abandoned sends are still running (design §15.3). Nothing was sent. */
  data object Busy : QuickChatOutcome

  /** The background ended without a result (design §9.2.4 (c)). */
  data object Interrupted : QuickChatOutcome

  /** An unexpected exception inside `submit`. Nothing was sent. */
  data object Failed : QuickChatOutcome

  /** `/clear` or `/reset` was handled (design §9.4). */
  data object Cleared : QuickChatOutcome

  /** The configured instance is no longer the bound one; the whole binding is dropped (§9.2.3). */
  data object ConnectionChanged : QuickChatOutcome
}
