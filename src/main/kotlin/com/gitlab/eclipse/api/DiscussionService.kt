package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.DiscussionDto
import com.gitlab.eclipse.api.model.PageInfoDto
import com.gitlab.eclipse.inject.service

/** Raw parse target for the GraphQL `MergeRequest.userPermissions` field this query selects. */
internal data class MrPermissionsDto(val createNote: Boolean?)

/** Raw parse target for the GraphQL `MergeRequest.discussions` connection. */
internal data class DiscussionConnectionDto(
  val pageInfo: PageInfoDto?,
  val nodes: List<DiscussionDto?>?,
)

/** Raw parse target for the GraphQL `MergeRequest` type this query selects. */
internal data class MergeRequestDto(
  val userPermissions: MrPermissionsDto?,
  val discussions: DiscussionConnectionDto?,
)

/** Raw parse target for the GraphQL `Project` type this query selects. */
internal data class ProjectDto(val id: String?, val mergeRequest: MergeRequestDto?)

/**
 * Raw parse target for [DiscussionService.GET_MR_DISCUSSIONS_QUERY]'s response `data` payload.
 * [GitLabGraphQlClient.execute] deserializes the response's `data` field directly into [T], so
 * this type starts at `project` and carries no `data` wrapper of its own. Every field is
 * nullable for the same reason as task 3's DTOs: Gson builds these through `Unsafe` and skips
 * constructor defaults, so a key missing from the response stays `null` whatever the declared
 * type says.
 */
internal data class DiscussionsQueryData(val project: ProjectDto?)

/**
 * Fetches a merge request's discussion threads over GraphQL (design phase5a-pr1). This task adds
 * only the protocol constants and identifier construction; the paging loop and deadline handling
 * that use [graphQlClient] are added by a later task appending to this same file. [graphQlClient]
 * is intentionally unused for now — do not delete it, and do not resolve the resulting detekt
 * finding by removing the parameter.
 */
@Suppress("UnusedPrivateProperty")
class DiscussionService(private val graphQlClient: GitLabGraphQlClient = service()) {

  companion object {

    /**
     * The `GetMrDiscussions` GraphQL query.
     *
     * Evidence:
     * - query skeleton and the `$namespaceWithPath: ID!, $iid: String!, $afterCursor: String`
     *   variable signature —
     *   `out/gitlab-vscode-extension/src/desktop/gitlab/graphql/get_discussions.ts:25-36`.
     * - the discussion / note / position field sets —
     *   `out/gitlab-vscode-extension/src/desktop/gitlab/graphql/shared.ts:3-60`.
     * - `mergeRequest.userPermissions { createNote }` is **not** part of the reference's
     *   discussions query; it comes from the reference's separate permissions query,
     *   `out/gitlab-vscode-extension/src/desktop/gitlab/graphql/mr_permission.ts:3-14`, folded
     *   into this query here so no extra round-trip is needed to learn whether the current user
     *   may comment.
     *
     * Deliberately omitted relative to the reference's field set: `author { avatarUrl name
     * webUrl }`, `bodyHtml`, `url`, `position { diffRefs { baseSha headSha startSha } filePath }`.
     * None of these are requested because no domain type in this PR carries them, and a smaller
     * selection means a smaller response — this is not an oversight, do not add them back.
     *
     * `$iid` is `String!`, not an integer — see [queryVariables] for the required
     * `mrIid.toString()` conversion. `$namespaceWithPath` is `ID!`.
     */
    const val GET_MR_DISCUSSIONS_QUERY = """
query GetMrDiscussions(${'$'}namespaceWithPath: ID!, ${'$'}iid: String!, ${'$'}afterCursor: String) {
  project(fullPath: ${'$'}namespaceWithPath) {
    id
    mergeRequest(iid: ${'$'}iid) {
      userPermissions { createNote }
      discussions(after: ${'$'}afterCursor) {
        pageInfo { hasNextPage endCursor }
        nodes {
          replyId
          createdAt
          resolved
          resolvable
          notes {
            pageInfo { hasNextPage endCursor }
            nodes {
              id
              createdAt
              system
              author { username }
              body
              userPermissions { resolveNote adminNote createNote }
              position {
                positionType
                newLine
                oldLine
                newPath
                oldPath
              }
            }
          }
        }
      }
    }
  }
}
"""

    /**
     * Builds the GraphQL global id for a merge request from its **`id`**, not its `iid` — these
     * are different numbers on GitLab and the GID form requires the global `id`. Confusing the
     * two here is the single easiest way to break every write in the next PR.
     *
     * Evidence: `out/gitlab-vscode-extension/src/desktop/gitlab/gitlab_service.ts:155-157`
     * (`getIssuableGqlId`/`getMrGqlId`: `` `gid://gitlab/${...MergeRequest...}/${issuable.id}` ``
     * and, specifically for merge requests, `` `gid://gitlab/MergeRequest/${id}` ``).
     */
    fun mrGid(id: Long): String = "gid://gitlab/MergeRequest/$id"

    /**
     * Extracts the `namespace/project` path from a merge request's
     * `GitLabMergeRequest.references.full` (e.g. `"group/subgroup/project!42"`), i.e. everything
     * before the first `#` or `!`. `references` and `references.full` are both nullable on
     * `GitLabMergeRequest` (`src/main/kotlin/com/gitlab/eclipse/api/model/GitLabMergeRequest.kt:19-21`);
     * this function itself takes a non-null [String] and performs no null handling — the caller
     * is responsible for absence.
     *
     * Evidence: `out/gitlab-vscode-extension/src/desktop/gitlab/gitlab_service.ts:154`
     * (`issuable.references.full.split(/[#!]/)[0]`).
     */
    fun namespaceWithPath(references: String): String = references.split('#', '!')[0]

    /**
     * Assembles the `GetMrDiscussions` variable map. `iid` is deliberately a [String]
     * (`mrIid.toString()`), never a number, because the GraphQL variable is declared `String!`
     * and passing a number is a request-level error that fails the whole query. `afterCursor` is
     * passed through as-is, including `null` for the first page — the key must still be present
     * so the variable is bound.
     *
     * Evidence for the string conversion:
     * `out/gitlab-vscode-extension/src/desktop/gitlab/gitlab_service.ts:466-475`
     * (`iid: String(mr.iid)`).
     */
    fun queryVariables(namespaceWithPath: String, mrIid: Long, afterCursor: String?): Map<String, Any?> = mapOf(
      "namespaceWithPath" to namespaceWithPath,
      "iid" to mrIid.toString(),
      "afterCursor" to afterCursor,
    )
  }
}
