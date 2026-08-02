package com.gitlab.eclipse.api.model

/** What the current user is allowed to do with a note. */
data class GitLabNotePermissions(
  val resolveNote: Boolean,
  val adminNote: Boolean,
  val createNote: Boolean,
)

/** A single note (comment) within a merge-request discussion thread. */
data class GitLabNote(
  val id: String,
  val createdAt: String,
  val system: Boolean,
  val authorUsername: String,
  val body: String,
  val permissions: GitLabNotePermissions,
  val position: GitLabNotePosition?,
)

/**
 * Raw parse target for the GraphQL `Note.userPermissions` field. Gson constructs this via Unsafe,
 * bypassing the constructor/default-argument evaluation, so any field absent from the JSON is
 * left `null` regardless of a non-null Kotlin type declaration — every field here is therefore
 * nullable, and [toDomain] normalizes it into the non-null [GitLabNotePermissions]. `internal`
 * because task 4/5's `DiscussionService` (same module, sibling `com.gitlab.eclipse.api` package)
 * parses the query envelope into this DTO and calls [toDomain].
 */
internal data class NotePermissionsDto(
  val resolveNote: Boolean?,
  val adminNote: Boolean?,
  val createNote: Boolean?,
)

/** Raw parse target for the GraphQL `Note.author` field. See [NotePermissionsDto] for why this is nullable/internal. */
internal data class NoteAuthorDto(val username: String?)

/** Raw parse target for the GraphQL `Note` type. See [NotePermissionsDto] for why this is nullable/internal. */
internal data class NoteDto(
  val id: String?,
  val createdAt: String?,
  val system: Boolean?,
  val author: NoteAuthorDto?,
  val body: String?,
  val userPermissions: NotePermissionsDto?,
  val position: NotePositionDto?,
)

internal fun NoteDto.toDomain(): GitLabNote = GitLabNote(
  id = id ?: "",
  createdAt = createdAt ?: "",
  system = system ?: false,
  authorUsername = author?.username ?: "",
  body = body ?: "",
  permissions = userPermissions.toDomain(),
  position = position?.toDomain(),
)

private fun NotePermissionsDto?.toDomain(): GitLabNotePermissions = GitLabNotePermissions(
  resolveNote = this?.resolveNote ?: false,
  adminNote = this?.adminNote ?: false,
  createNote = this?.createNote ?: false,
)
