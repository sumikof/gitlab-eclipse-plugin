package com.gitlab.eclipse.api.model

/**
 * Where a diff note is anchored, if at all. Most notes are plain discussion notes with no
 * position; a note on a specific diff line carries [newPath]/[newLine] or [oldPath]/[oldLine].
 */
data class GitLabNotePosition(
  val positionType: String,
  val newPath: String?,
  val oldPath: String?,
  val newLine: Int?,
  val oldLine: Int?,
)

/**
 * Raw parse target for the GraphQL `Note.position` field. Gson constructs this via Unsafe,
 * bypassing the constructor/default-argument evaluation, so any field absent from the JSON is
 * left `null` regardless of a non-null Kotlin type declaration — every field here is therefore
 * nullable, and [toDomain] normalizes it into the non-null [GitLabNotePosition]. `internal`
 * because task 4/5's `DiscussionService` (same module, sibling `com.gitlab.eclipse.api` package)
 * parses the query envelope into this DTO and calls [toDomain].
 */
internal data class NotePositionDto(
  val positionType: String?,
  val newPath: String?,
  val oldPath: String?,
  val newLine: Int?,
  val oldLine: Int?,
)

internal fun NotePositionDto.toDomain(): GitLabNotePosition = GitLabNotePosition(
  positionType = positionType ?: "text",
  newPath = newPath,
  oldPath = oldPath,
  newLine = newLine,
  oldLine = oldLine,
)
