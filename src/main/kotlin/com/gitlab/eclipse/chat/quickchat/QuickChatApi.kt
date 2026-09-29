package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabGraphQlClient
import com.gitlab.eclipse.inject.service
import com.google.gson.JsonSyntaxException
import java.time.Duration

/** The `aiAction` payload of a question (design §11.2 M1, K6). [threadId] is null before GitLab 17.10 (K3). */
data class AskResponse(val requestId: String?, val errors: List<String>, val threadId: String?)

/** The `aiAction` payload of `/clear` / `/reset` (design §11.2 M2). */
data class ClearResponse(val requestId: String?, val errors: List<String>)

/**
 * The project facts preflight needs (design §9.2.2, K10, K11). [id] is the GraphQL global id sent
 * as `resourceId`; [duoFeaturesEnabled] is null when the server does not say.
 */
data class ProjectInfo(val id: String, val duoFeaturesEnabled: Boolean?)

/** One `aiMessages` node (design §11.2 Q2, K7). Every field is as the server sent it, possibly null. */
data class AiMessageNode(
  val requestId: String?,
  val role: String?,
  val content: String?,
  val errors: List<String>?,
  val timestamp: String?,
)

/**
 * The Quick Chat GraphQL operations (design §11.2). Every call is blocking, pinned to one
 * [ConnectionSnapshot], and bounded by the caller's `timeout`. Transport failures from
 * [GitLabGraphQlClient] propagate unchanged — classifying them is the caller's job.
 */
interface QuickChatApi {

  /** M1. Sends [question]; [threadId] only from the second question on (R4). */
  fun ask(
    connection: ConnectionSnapshot,
    question: String,
    currentFile: CurrentFile?,
    resourceId: String?,
    threadId: String?,
    clientSubscriptionId: String,
    timeout: Duration,
  ): AskResponse

  /** M2. [command] is `/clear` or `/reset`, sent as the question of the thread [threadId] (R5). */
  fun clear(connection: ConnectionSnapshot, command: String, threadId: String, timeout: Duration): ClearResponse

  /** Q1a. The instance version, or null when `metadata` (or its version) is null. */
  fun version(connection: ConnectionSnapshot, timeout: Duration): String?

  /** Q1b. The project at [fullPath] (already decoded, see [decodeFullPath]), or null when not visible. */
  fun project(connection: ConnectionSnapshot, fullPath: String, timeout: Duration): ProjectInfo?

  /** Q2. The ASSISTANT messages of [requestId] in [threadId]; empty while none is stored yet (K2). */
  fun messages(
    connection: ConnectionSnapshot,
    requestId: String,
    threadId: String,
    timeout: Duration,
  ): List<AiMessageNode>

  companion object {
    /** Any string is accepted by the server (K5); identifies this client in GitLab's own telemetry. */
    const val PLATFORM_ORIGIN = "eclipse_plugin"

    /** K8. Keeps Quick Chat threads apart from the sidebar Duo Chat threads. */
    const val CONVERSATION_TYPE = "DUO_QUICK_CHAT"
  }
}

// Raw Gson parse targets. Every field (and list element) is nullable because Gson builds these
// through Unsafe and skips Kotlin's null checks, so a non-null declaration would lie at runtime.
internal data class AiActionPayloadDto(val requestId: String?, val errors: List<String?>?, val threadId: String?)
internal data class AiActionData(val aiAction: AiActionPayloadDto?)
internal data class MetadataDto(val version: String?)
internal data class VersionData(val metadata: MetadataDto?)
internal data class ProjectDto(val id: String?, val duoFeaturesEnabled: Boolean?)
internal data class ProjectData(val project: ProjectDto?)
internal data class AiMessageDto(
  val requestId: String?,
  val role: String?,
  val content: String?,
  val errors: List<String?>?,
  val timestamp: String?,
)
internal data class AiMessageConnectionDto(val nodes: List<AiMessageDto?>?)
internal data class AiMessagesData(val aiMessages: AiMessageConnectionDto?)

/**
 * [QuickChatApi] over [GitLabGraphQlClient]. This class logs nothing: questions, answers, file
 * contents and server error strings (which can echo the question) must never reach logs (NFR-4).
 */
class GraphQlQuickChatApi(
  private val graphQl: GitLabGraphQlClient = service(),
) : QuickChatApi {

  override fun ask(
    connection: ConnectionSnapshot,
    question: String,
    currentFile: CurrentFile?,
    resourceId: String?,
    threadId: String?,
    clientSubscriptionId: String,
    timeout: Duration,
  ): AskResponse {
    val variables = buildMap<String, Any?> {
      put("question", question)
      put("clientSubscriptionId", clientSubscriptionId)
      put("platformOrigin", QuickChatApi.PLATFORM_ORIGIN)
      put("conversationType", QuickChatApi.CONVERSATION_TYPE)
      // Absent keys, not nulls: an unbound variable leaves the input field absent, which is what
      // "new thread" (K4), "no project" and "no selection" (R3) mean.
      resourceId?.let { put("resourceId", it) }
      threadId?.let { put("threadId", it) }
      currentFile?.let { put("currentFile", currentFileVariable(it)) }
    }
    val payload = graphQl.execute(ASK_MUTATION, variables, AiActionData::class.java, connection, timeout).aiAction
      ?: throw JsonSyntaxException(MISSING_PAYLOAD_MESSAGE)
    return AskResponse(payload.requestId, payload.errors?.filterNotNull().orEmpty(), payload.threadId)
  }

  override fun clear(connection: ConnectionSnapshot, command: String, threadId: String, timeout: Duration): ClearResponse {
    val variables = mapOf(
      "question" to command,
      "threadId" to threadId,
      "platformOrigin" to QuickChatApi.PLATFORM_ORIGIN,
    )
    val payload = graphQl.execute(CLEAR_MUTATION, variables, AiActionData::class.java, connection, timeout).aiAction
      ?: throw JsonSyntaxException(MISSING_PAYLOAD_MESSAGE)
    return ClearResponse(payload.requestId, payload.errors?.filterNotNull().orEmpty())
  }

  override fun version(connection: ConnectionSnapshot, timeout: Duration): String? =
    graphQl.execute(VERSION_QUERY, emptyMap(), VersionData::class.java, connection, timeout).metadata?.version

  override fun project(connection: ConnectionSnapshot, fullPath: String, timeout: Duration): ProjectInfo? {
    val variables = mapOf("fullPath" to fullPath)
    val project = graphQl.execute(PROJECT_QUERY, variables, ProjectData::class.java, connection, timeout).project
    // Without an id there is no resourceId to scope the question to, so preflight must not proceed.
    val id = project?.id ?: return null
    return ProjectInfo(id, project.duoFeaturesEnabled)
  }

  override fun messages(
    connection: ConnectionSnapshot,
    requestId: String,
    threadId: String,
    timeout: Duration,
  ): List<AiMessageNode> {
    val variables = mapOf(
      "requestIds" to listOf(requestId),
      "roles" to listOf(ASSISTANT_ROLE),
      "threadId" to threadId,
    )
    val nodes = graphQl.execute(MESSAGES_QUERY, variables, AiMessagesData::class.java, connection, timeout)
      .aiMessages?.nodes.orEmpty()
    return nodes.filterNotNull().map {
      AiMessageNode(it.requestId, it.role, it.content, it.errors?.filterNotNull(), it.timestamp)
    }
  }

  private fun currentFileVariable(file: CurrentFile): Map<String, Any?> = mapOf(
    "fileName" to file.fileName,
    "selectedText" to file.selectedText,
    "contentAboveCursor" to file.contentAboveCursor,
    "contentBelowCursor" to file.contentBelowCursor,
  )

  companion object {
    /**
     * The message of the [JsonSyntaxException] thrown when a mutation's `aiAction` payload is
     * absent: the question may or may not have been accepted, so it must land in the "result
     * unknown" bucket, like any uninterpretable response. Constant so no server text reaches it.
     */
    const val MISSING_PAYLOAD_MESSAGE = "GraphQL response carried no aiAction payload"

    private const val ASSISTANT_ROLE = "ASSISTANT"

    /** Design §11.2 M1 (REF `chat/gitlab_chat_api.ts:117-148`, 17.10 form, without `additionalContext`). */
    const val ASK_MUTATION = """
mutation quickChatAsk(${'$'}question: String!, ${'$'}resourceId: AiModelID, ${'$'}currentFile: AiCurrentFileInput,
  ${'$'}clientSubscriptionId: String, ${'$'}platformOrigin: String!,
  ${'$'}conversationType: AiConversationsThreadsConversationType, ${'$'}threadId: AiConversationThreadID) {
  aiAction(input: {
    chat: { resourceId: ${'$'}resourceId, content: ${'$'}question, currentFile: ${'$'}currentFile }
    clientSubscriptionId: ${'$'}clientSubscriptionId
    platformOrigin: ${'$'}platformOrigin
    conversationType: ${'$'}conversationType
    threadId: ${'$'}threadId
  }) { requestId errors threadId }
}
"""

    /** Design §11.2 M2 (R5). */
    const val CLEAR_MUTATION = """
mutation quickChatClear(${'$'}question: String!, ${'$'}threadId: AiConversationThreadID, ${'$'}platformOrigin: String!) {
  aiAction(input: { chat: { content: ${'$'}question }, threadId: ${'$'}threadId, platformOrigin: ${'$'}platformOrigin }) {
    requestId errors
  }
}
"""

    /** Design §11.2 Q1, first document. Kept apart from [PROJECT_QUERY] so a pre-16.9 schema still answers it. */
    const val VERSION_QUERY = """
query quickChatVersion { metadata { version } }
"""

    /** Design §11.2 Q1, second document (K10, K11). */
    const val PROJECT_QUERY = """
query quickChatProject(${'$'}fullPath: ID!) { project(fullPath: ${'$'}fullPath) { id duoFeaturesEnabled } }
"""

    /** Design §11.2 Q2 (K1, K7). */
    const val MESSAGES_QUERY = """
query quickChatMessages(${'$'}requestIds: [ID!], ${'$'}roles: [AiMessageRole!], ${'$'}threadId: AiConversationThreadID) {
  aiMessages(requestIds: ${'$'}requestIds, roles: ${'$'}roles, threadId: ${'$'}threadId) {
    nodes { requestId role content errors timestamp }
  }
}
"""
  }
}
