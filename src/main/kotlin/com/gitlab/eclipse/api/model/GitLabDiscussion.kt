package com.gitlab.eclipse.api.model

/**
 * A merge-request discussion thread: a reply-anchored root note plus its replies. This project
 * deliberately does not page notes within a thread — [hasMoreNotes] signals to the UI that more
 * replies exist than were fetched, so it can show a marker instead of silently dropping them.
 */
data class GitLabDiscussion(
  val replyId: String,
  val createdAt: String,
  val resolved: Boolean,
  val resolvable: Boolean,
  val notes: List<GitLabNote>,
  val hasMoreNotes: Boolean,
)

/**
 * Raw parse target for a GraphQL connection's `pageInfo` field. Also reused by task 4/5 for the
 * outer `discussions.pageInfo` connection — do not duplicate this type elsewhere.
 */
internal data class PageInfoDto(
  val hasNextPage: Boolean?,
  val endCursor: String?,
)

/**
 * Raw parse target for the GraphQL `Discussion.notes` connection. See [DiscussionDto] for why
 * this is nullable/internal.
 */
internal data class NoteConnectionDto(
  val pageInfo: PageInfoDto?,
  val nodes: List<NoteDto?>?,
)

/**
 * Raw parse target for the GraphQL `Discussion` type. Gson constructs this via Unsafe, bypassing
 * the constructor/default-argument evaluation, so any field absent from the JSON is left `null`
 * regardless of a non-null Kotlin type declaration — every field here is therefore nullable, and
 * [toDomain] normalizes it into the non-null [GitLabDiscussion]. `internal` because task 4/5's
 * `DiscussionService` (same module, sibling `com.gitlab.eclipse.api` package) parses the query
 * envelope into this DTO and calls [toDomain].
 */
internal data class DiscussionDto(
  val replyId: String?,
  val createdAt: String?,
  val resolved: Boolean?,
  val resolvable: Boolean?,
  val notes: NoteConnectionDto?,
)

internal fun DiscussionDto.toDomain(): GitLabDiscussion = GitLabDiscussion(
  replyId = replyId ?: "",
  createdAt = createdAt ?: "",
  resolved = resolved ?: false,
  resolvable = resolvable ?: false,
  notes = notes?.nodes.orEmpty().filterNotNull().map { it.toDomain() },
  hasMoreNotes = notes?.pageInfo?.hasNextPage ?: false,
)
