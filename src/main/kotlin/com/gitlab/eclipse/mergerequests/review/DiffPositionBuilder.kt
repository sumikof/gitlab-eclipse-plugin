package com.gitlab.eclipse.mergerequests.review

/** Outcome of [buildPosition]: either the GraphQL variables to send, or why they cannot be built. */
sealed interface PositionResult {
  /** [variables] is the `DiffPositionInput` GraphQL variable map (design §12.3), ready to send. */
  data class Ready(val variables: Map<String, Any?>) : PositionResult

  /** No position could be built for the requested line; see [RefuseReason]. */
  data class Refused(val reason: RefuseReason) : PositionResult
}

/** Why [buildPosition] refused to build a position. */
enum class RefuseReason {
  /** [DiffLineMap] has no line mapping for this file (design §14: "too large to comment on"). */
  DIFF_UNAVAILABLE,

  /** The requested line falls outside `1..lineCount` of the current document. */
  LINE_OUT_OF_RANGE,
}

/**
 * Builds the `DiffPositionInput` GraphQL variables for `createDiffNote` at [oneBasedLine] in the
 * new file named [newPath] (design §12.3, §18: input validation). Checked in this order:
 *
 * 1. [oneBasedLine] must fall within `1..[lineCount]`; otherwise [RefuseReason.LINE_OUT_OF_RANGE].
 *    This is checked before consulting [map], so an out-of-range line is refused even against
 *    [DiffLineMap.Unavailable].
 * 2. [map] must classify the line; [DiffLineMap.Unavailable] yields [RefuseReason.DIFF_UNAVAILABLE].
 *
 * The returned variables always carry `baseSha`/`headSha`/`startSha` (from [refs]),
 * `paths = {oldPath, newPath}`, and `newLine`. For an added line, the `oldLine` key is omitted
 * entirely — not sent even as a `null` value — since GitLab rejects `oldLine` on a pure addition;
 * for an unchanged line, `oldLine` is included (design §12.3).
 */
fun buildPosition(
  refs: VersionRefs,
  oldPath: String,
  newPath: String,
  map: DiffLineMap,
  oneBasedLine: Int,
  lineCount: Int,
): PositionResult {
  if (oneBasedLine !in 1..lineCount) return PositionResult.Refused(RefuseReason.LINE_OUT_OF_RANGE)
  val kind = map.classify(oneBasedLine) ?: return PositionResult.Refused(RefuseReason.DIFF_UNAVAILABLE)

  val variables = mutableMapOf<String, Any?>(
    "baseSha" to refs.baseSha,
    "headSha" to refs.headSha,
    "startSha" to refs.startSha,
    "paths" to mapOf("oldPath" to oldPath, "newPath" to newPath),
    "newLine" to oneBasedLine,
  )
  if (kind is NewLineKind.Unchanged) {
    variables["oldLine"] = kind.oldLine
  }
  return PositionResult.Ready(variables)
}
