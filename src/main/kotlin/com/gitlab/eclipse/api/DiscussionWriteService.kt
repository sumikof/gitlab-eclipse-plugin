package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.GitLabRestNote
import com.gitlab.eclipse.inject.service
import com.google.gson.JsonSyntaxException
import java.time.Duration

/**
 * Raw parse target for a mutation's payload object (`createNote`, `discussionToggleResolve`,
 * `updateNote`, or `destroyNote` under `data`). Only `errors` is selected — see the KDoc on
 * [DiscussionWriteService.CREATE_NOTE_MUTATION] for why the reference's `note { ...noteDetails }`
 * selection is deliberately not reproduced. Both the list and its elements are nullable because
 * Gson builds these through `Unsafe` and skips Kotlin constructors, so a non-null declaration
 * does not make a field non-null at runtime (the bug class behind issue #47).
 */
internal data class MutationPayloadDto(val errors: List<String?>?)

/**
 * Raw parse target for [DiscussionWriteService.CREATE_NOTE_MUTATION]'s `data` payload. The field
 * name must equal the mutation field selected in the query (`createNote`) or Gson silently
 * yields null.
 */
internal data class CreateNoteData(val createNote: MutationPayloadDto?)

/**
 * Raw parse target for [DiscussionWriteService.TOGGLE_RESOLVE_MUTATION]'s `data` payload; the
 * field name must equal the selected mutation field, `discussionToggleResolve`.
 */
internal data class ToggleResolveData(val discussionToggleResolve: MutationPayloadDto?)

/**
 * Raw parse target for [DiscussionWriteService.UPDATE_NOTE_MUTATION]'s `data` payload; the field
 * name must equal the selected mutation field, `updateNote`.
 */
internal data class UpdateNoteData(val updateNote: MutationPayloadDto?)

/**
 * Raw parse target for [DiscussionWriteService.DESTROY_NOTE_MUTATION]'s `data` payload; the field
 * name must equal the selected mutation field, which is `destroyNote` — NOT `deleteNote`, even
 * though the operation is named `DeleteNote`.
 */
internal data class DestroyNoteData(val destroyNote: MutationPayloadDto?)

private val NOTE_GID_TAIL = Regex("""^gid://gitlab/[A-Za-z]+/(\d+)$""")

/**
 * Extracts the numeric REST id from a GraphQL global id, e.g. `gid://gitlab/Note/12345` → `"12345"`.
 * Returns null when the input is not a well-formed numeric-tailed GID. The type segment varies
 * (`Note`, `DiffNote`, …), so it is matched loosely and only the trailing id is taken.
 */
fun restIdFromGid(gid: String): String? = NOTE_GID_TAIL.matchEntire(gid)?.groupValues?.get(1)

/**
 * Issues the four MR-discussion write mutations (create note / toggle resolve / update note /
 * delete note) over GraphQL. Owns the mutation constants and their variable-map builders, and
 * performs the L3 payload inspection: every write goes through [requireNoPayloadErrors], which
 * distinguishes a payload the server explicitly refused from a payload that is missing entirely —
 * see that function for why the two must never be collapsed.
 *
 * [DiscussionMutationException] is deliberately NOT a [GraphQlException]:
 * `classifyWriteFailure` treats [DiscussionMutationException] as Definite (the server states it
 * executed the mutation and refused it — no side effect) but any [GraphQlException] with
 * `hasDataKey = true` as Ambiguous. L1 ([GitLabApiException]) and L2 ([GraphQlException]) failures
 * from [GitLabGraphQlClient.execute] propagate out of this class unchanged so that
 * classification survives.
 *
 * All four write functions return [Unit] and signal failure only by throwing; success always
 * triggers a full re-fetch by the caller, which is why no mutation selects the written note back.
 *
 * This class logs nothing: note bodies must never reach logs, and GitLab's payload error strings
 * can echo the submitted body.
 */
class DiscussionWriteService(
  private val graphQlClient: GitLabGraphQlClient = service(),
  private val apiClient: GitLabApiClient = service(),
) {

  /**
   * Creates a note: a reply to the discussion identified by [replyId], or — when [replyId] is
   * `null` — a new MR-level (non-line-anchored) comment thread on the issuable [issuableId]
   * (a `gid://gitlab/MergeRequest/<id>` built by [DiscussionService.mrGid]).
   * [mergeRequestDiffHeadSha] is the MR `sha` (nullable on the sidebar node); GitLab uses it to
   * reject the write when the MR head moved since the user last saw it.
   */
  fun createNote(
    connection: ConnectionSnapshot,
    issuableId: String,
    body: String,
    replyId: String?,
    mergeRequestDiffHeadSha: String?,
  ) {
    val data = graphQlClient.execute(
      CREATE_NOTE_MUTATION,
      createNoteVariables(issuableId, body, replyId, mergeRequestDiffHeadSha),
      CreateNoteData::class.java,
      connection,
      WRITE_TIMEOUT,
    )
    requireNoPayloadErrors(data.createNote)
  }

  /**
   * Sets the resolved state of the discussion identified by [replyId] to [resolved].
   * **[resolved] is a target state, not a toggle**, despite the mutation's name: `resolve: true`
   * means "make it resolved", `resolve: false` means "make it unresolved". Never negate or
   * flip the flag on the way through.
   */
  fun toggleResolve(connection: ConnectionSnapshot, replyId: String, resolved: Boolean) {
    val data = graphQlClient.execute(
      TOGGLE_RESOLVE_MUTATION,
      toggleResolveVariables(replyId, resolved),
      ToggleResolveData::class.java,
      connection,
      WRITE_TIMEOUT,
    )
    requireNoPayloadErrors(data.discussionToggleResolve)
  }

  /** Replaces the body of the note identified by [noteGid] (a `gid://gitlab/Note/<id>`) with [body]. */
  fun updateNote(connection: ConnectionSnapshot, noteGid: String, body: String) {
    val data = graphQlClient.execute(
      UPDATE_NOTE_MUTATION,
      updateNoteVariables(noteGid, body),
      UpdateNoteData::class.java,
      connection,
      WRITE_TIMEOUT,
    )
    requireNoPayloadErrors(data.updateNote)
  }

  /** Deletes the note identified by [noteGid] (a `gid://gitlab/Note/<id>`). */
  fun destroyNote(connection: ConnectionSnapshot, noteGid: String) {
    val data = graphQlClient.execute(
      DESTROY_NOTE_MUTATION,
      destroyNoteVariables(noteGid),
      DestroyNoteData::class.java,
      connection,
      WRITE_TIMEOUT,
    )
    requireNoPayloadErrors(data.destroyNote)
  }

  /**
   * Design §13. Fetches the note over REST and refuses the edit when the server's body differs from
   * [expectedBody]. Narrows — but cannot close — the TOCTOU window, because GitLab's `updateNote`
   * has no optimistic locking.
   */
  fun assertNoteUnchanged(
    connection: ConnectionSnapshot,
    projectId: Long,
    mrIid: Long,
    noteGid: String,
    expectedBody: String,
  ) {
    val restId = restIdFromGid(noteGid) ?: throw IllegalArgumentException("Unrecognized note id")
    // Declared nullable on purpose: fetchObject's return type is a non-null generic, but it is
    // produced by Gson.fromJson, which yields null for an empty or literal-null 2xx body and
    // bypasses Kotlin's null checks entirely (follow-up #47). Treating an absent object the same
    // way as an absent body keeps this fail-closed: we cannot prove the note is unchanged, so we
    // refuse the edit rather than overwriting whatever is really there.
    val fetched: GitLabRestNote? = apiClient.fetchObject(
      "/projects/$projectId/merge_requests/$mrIid/notes/$restId",
      emptyMap(),
      GitLabRestNote::class.java,
      connection,
    )
    if (fetched?.body != expectedBody) throw NoteChangedException()
  }

  /**
   * The L3 check every write goes through. Both failure modes are failures, but they are **not the
   * same failure**, and the difference decides whether the user is offered `[Retry]`:
   *
   * - **Payload missing** (`payload == null`) → [JsonSyntaxException] with a constant message,
   *   which `classifyWriteFailure` maps to **Ambiguous**. A 2xx response that carries no payload
   *   object for the mutation field proves *nothing* about whether the mutation ran: the server may
   *   well have committed the note and only the response came back incomplete (a proxy truncation,
   *   a schema/field-name drift, an unexpected `null` from Gson). Classifying it Definite would
   *   offer `[Retry]` and let the user post the same comment twice. Ambiguous instead routes them
   *   through the forced re-fetch, so `[Send again]` is only reachable after they have seen the
   *   current state. The message is a **constant** on purpose: no server text may reach it, because
   *   GitLab's error strings can echo the submitted comment body.
   * - **Payload present with a non-empty `errors` array** → [DiscussionMutationException], which
   *   maps to **Definite**. Here the server explicitly stated it executed the mutation and refused
   *   it, so there is no side effect and `[Retry]` is provably safe.
   * - **Payload present with an empty or absent `errors` array** → success.
   *
   * `null` elements inside `errors` are dropped rather than stringified.
   */
  private fun requireNoPayloadErrors(payload: MutationPayloadDto?) {
    if (payload == null) throw JsonSyntaxException(MISSING_PAYLOAD_MESSAGE)
    val messages = payload.errors?.filterNotNull().orEmpty()
    if (messages.isNotEmpty()) throw DiscussionMutationException(messages)
  }

  companion object {

    /**
     * The [JsonSyntaxException] message used when a mutation's payload object is absent. Constant
     * and server-free by construction — see [requireNoPayloadErrors].
     */
    const val MISSING_PAYLOAD_MESSAGE = "GraphQL response carried no payload for the mutation field"

    /**
     * The `CreateNote` GraphQL mutation.
     *
     * Evidence: `out/gitlab-vscode-extension/src/desktop/gitlab/graphql/create_note.ts:18-40`
     * (`newCreateNoteMutation` — the GitLab >= 14.9 variant with `$mergeRequestDiffHeadSha`;
     * the version gate that would fall back to `oldCreateNoteMutation` is not reproduced here).
     *
     * Deliberate, approved deviation from design §10.3: the design (and the reference) select
     * `note { ...noteDetails }` in addition to `errors`; here every mutation selects
     * **`{ errors }` only**, because (a) the `noteDetails` fragment is defined neither in the
     * design nor in this repo and inventing a protocol constant is forbidden, (b) the returned
     * note is never used — success always triggers a full re-fetch, and (c) fewer selected
     * fields structurally shrink the L2 field-level-error surface that would otherwise be
     * classified Ambiguous. **Do not add `note { … }` back.**
     */
    const val CREATE_NOTE_MUTATION = """
mutation CreateNote(${'$'}issuableId: NoteableID!, ${'$'}body: String!, ${'$'}replyId: DiscussionID, ${'$'}mergeRequestDiffHeadSha: String) {
  createNote(input: { noteableId: ${'$'}issuableId, body: ${'$'}body, discussionId: ${'$'}replyId, mergeRequestDiffHeadSha: ${'$'}mergeRequestDiffHeadSha }) {
    errors
  }
}
"""

    /**
     * The `DiscussionToggleResolve` GraphQL mutation. Despite the name, `resolve` is a target
     * state, not a toggle — see [toggleResolve].
     *
     * Evidence: `out/gitlab-vscode-extension/src/desktop/gitlab/gitlab_service.ts:128-134`
     * (`discussionSetResolved` — the reference itself selects `{ errors }` only here).
     */
    const val TOGGLE_RESOLVE_MUTATION = """
mutation DiscussionToggleResolve(${'$'}replyId: DiscussionID!, ${'$'}resolved: Boolean!) {
  discussionToggleResolve(input: { id: ${'$'}replyId, resolve: ${'$'}resolved }) {
    errors
  }
}
"""

    /**
     * The `UpdateNoteBody` GraphQL mutation. `$body` is nullable (`String`, not `String!`) in the
     * reference and is kept that way — changing a variable's declared type is a protocol change.
     *
     * Evidence: `out/gitlab-vscode-extension/src/desktop/gitlab/gitlab_service.ts:146-152`
     * (`updateNoteBodyMutation` — the reference itself selects `{ errors }` only here).
     */
    const val UPDATE_NOTE_MUTATION = """
mutation UpdateNoteBody(${'$'}noteId: NoteID!, ${'$'}body: String) {
  updateNote(input: { id: ${'$'}noteId, body: ${'$'}body }) {
    errors
  }
}
"""

    /**
     * The `DeleteNote` GraphQL mutation. The operation name is `DeleteNote` but the mutation
     * field — and therefore [DestroyNoteData]'s property — is `destroyNote`.
     *
     * Evidence: `out/gitlab-vscode-extension/src/desktop/gitlab/gitlab_service.ts:137-143`
     * (`deleteNoteMutation` — the reference itself selects `{ errors }` only here).
     */
    const val DESTROY_NOTE_MUTATION = """
mutation DeleteNote(${'$'}noteId: NoteID!) {
  destroyNote(input: { id: ${'$'}noteId }) {
    errors
  }
}
"""

    /** Timeout for a single write request, mirroring [DiscussionService]'s single-request cap. */
    val WRITE_TIMEOUT: Duration = Duration.ofSeconds(30)

    /**
     * Assembles the `CreateNote` variable map. `replyId = null` means an MR-level comment (a
     * new, non-line-anchored thread); the key is kept in the map (rather than omitted) for shape
     * stability and testability, and the same holds for a null [mergeRequestDiffHeadSha].
     * [GitLabGraphQlClient] serializes with a default `Gson()` (`GitLabGraphQlClient.kt:45,72`),
     * and Gson's default serializer **drops null map entries from the wire**, so a null `replyId`
     * (or `mergeRequestDiffHeadSha`) is never transmitted and the corresponding GraphQL variable
     * ends up unbound — matching the reference's `undefined`, which `JSON.stringify` drops the
     * same way (`gitlab_service.ts:531-532`). An unbound nullable variable with no default makes
     * the input field absent, which is exactly what "an MR-level comment" (or "no diff head sha")
     * means. **Nothing here may be "fixed" by enabling `serializeNulls`**: doing so would start
     * transmitting an explicit null `discussionId`, changing the meaning of the request.
     *
     * Evidence for the key names:
     * `out/gitlab-vscode-extension/src/desktop/gitlab/gitlab_service.ts:528-533`.
     */
    fun createNoteVariables(
      issuableId: String,
      body: String,
      replyId: String?,
      mergeRequestDiffHeadSha: String?,
    ): Map<String, Any?> = mapOf(
      "issuableId" to issuableId,
      "body" to body,
      "replyId" to replyId,
      "mergeRequestDiffHeadSha" to mergeRequestDiffHeadSha,
    )

    /**
     * Assembles the `DiscussionToggleResolve` variable map. [resolved] is the target state —
     * passed through unmodified, never negated.
     *
     * Evidence for the key names:
     * `out/gitlab-vscode-extension/src/desktop/gitlab/gitlab_service.ts:484-487`.
     */
    fun toggleResolveVariables(replyId: String, resolved: Boolean): Map<String, Any?> = mapOf(
      "replyId" to replyId,
      "resolved" to resolved,
    )

    /**
     * Assembles the `UpdateNoteBody` variable map.
     *
     * Evidence for the key names:
     * `out/gitlab-vscode-extension/src/desktop/gitlab/gitlab_service.ts:595-598`.
     */
    fun updateNoteVariables(noteGid: String, body: String): Map<String, Any?> = mapOf(
      "noteId" to noteGid,
      "body" to body,
    )

    /**
     * Assembles the `DeleteNote` variable map.
     *
     * Evidence for the key name:
     * `out/gitlab-vscode-extension/src/desktop/gitlab/gitlab_service.ts:551-553`.
     */
    fun destroyNoteVariables(noteGid: String): Map<String, Any?> = mapOf(
      "noteId" to noteGid,
    )
  }
}
