package com.gitlab.eclipse.api.model

/**
 * The three merge-request-version commits a diff-anchored note's position was taken against
 * (design §11.2, §12.2, GraphQL `DiffRefs`): `baseSha`, `headSha`, and `startSha`. A later task's
 * `ThreadPlacement` compares this against the current MR version's own `(baseSha, headSha,
 * startSha)` to decide whether the note's position is still current (G-4/FR-3/A2) — a mismatch
 * means the note is stale and is not annotated in the editor. This is a *read-side* concept about
 * an *existing* note; it is unrelated to `DiffPositionInput`, the shape `createDiffNote` sends
 * when creating a *new* note, which this project builds from `GitLabMrVersion`, not from this
 * type. Every field is nullable for the same reason as [NotePositionDto]: Gson constructs this via
 * Unsafe, bypassing the constructor, so a field absent from the response stays `null` regardless
 * of the declared type.
 */
data class GitLabDiffRefs(
  val baseSha: String?,
  val headSha: String?,
  val startSha: String?,
)
