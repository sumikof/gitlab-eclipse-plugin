package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.DiscussionDto
import com.gitlab.eclipse.api.model.GitLabDiscussion
import com.gitlab.eclipse.api.model.PageInfoDto
import com.gitlab.eclipse.api.model.toDomain
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.logger
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

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
 * Why a [DiscussionsReadResult] does not necessarily contain every discussion on the merge
 * request. [MISSING_CURSOR] means a page said `hasNextPage = true` but supplied no `endCursor`
 * to advance with: more pages exist, they just cannot be fetched — a truncation, never a
 * complete fetch.
 */
enum class TruncationReason { PAGE_LIMIT, DEADLINE, MISSING_CURSOR }

/**
 * Result of [DiscussionService.getDiscussions]. [discussions] holds everything fetched before
 * [truncation] (if non-null) stopped the loop, filtered and sorted as described on
 * [DiscussionService.getDiscussions]. [canCreateNote] is
 * `mergeRequest.userPermissions.createNote` taken from the **first** page's response — see that
 * method's KDoc for why a later page's value must not overwrite it. `truncation == null` means
 * every discussion was fetched.
 */
data class DiscussionsReadResult(
  val canCreateNote: Boolean,
  val discussions: List<GitLabDiscussion>,
  val truncation: TruncationReason?,
)

/**
 * Fetches a merge request's discussion threads over GraphQL. Holds the query constants
 * ([GET_MR_DISCUSSIONS_QUERY], [mrGid], [namespaceWithPath], [queryVariables]) and the identifier
 * construction they need, and runs a paged fetch of the outer `discussions` connection through
 * [graphQlClient], bounded by a wall-clock deadline and by [MAX_DISCUSSION_PAGES] — see
 * [getDiscussions].
 */
class DiscussionService(private val graphQlClient: GitLabGraphQlClient = service()) {

  private val logger by lazy { logger<DiscussionService>() }

  companion object {

    /**
     * The `GetMrDiscussions` GraphQL query.
     *
     * Evidence:
     * - query skeleton and the `$namespaceWithPath: ID!, $iid: String!, $afterCursor: String`
     *   variable signature —
     *   `out/gitlab-vscode-extension/src/desktop/gitlab/graphql/get_discussions.ts:25-36`.
     * - the discussion / note / position field sets —
     *   `out/gitlab-vscode-extension/src/desktop/gitlab/graphql/shared.ts:3-63`.
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

    /** Cap on outer `discussions` pages fetched per call, mirroring [GitLabApiClient]'s `MAX_PAGES`. */
    const val MAX_DISCUSSION_PAGES = 20

    /** Overall wall-clock budget a caller should pass to [getDiscussions] absent a smaller test value. */
    val DISCUSSIONS_DEADLINE: Duration = Duration.ofSeconds(60)

    /** Ceiling on a single GraphQL request's timeout, independent of how much deadline budget remains. */
    private val SINGLE_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
  }

  /**
   * Fetches every discussion thread on a merge request, paging the outer `discussions` connection
   * until it is exhausted, [deadline] elapses, or [MAX_DISCUSSION_PAGES] is reached.
   *
   * Only the **outer** `discussions` connection is paged. Each discussion's `notes` connection
   * also carries a `pageInfo`, but the query has no variable to advance it and the reference
   * implementation never pages it either (it selects `notes.pageInfo` and never uses it —
   * `out/gitlab-vscode-extension/src/desktop/gitlab/gitlab_service.ts:448-462`); inventing a
   * note-paging query would violate this project's "protocol constants come from real source"
   * rule. Instead a discussion whose notes were truncated server-side surfaces that via
   * [GitLabDiscussion.hasMoreNotes]. This method never loops over notes.
   *
   * [clock] and [isActive] mirror [GitLabApiClient.fetchListWithinDeadline]
   * (`GitLabApiClient.kt:112-143`): [clock] is checked at the top of every iteration against
   * [deadline] (elapsed since the call started), and [isActive] returning `false` there throws
   * [CancellationException] before any further request is issued. Reaching the deadline itself is
   * not an error: the loop returns what it has with `truncation = `[TruncationReason.DEADLINE].
   * The same outcome is produced whether the deadline is noticed at the top of the loop or as an
   * [HttpTimeoutException] from a request whose timeout was itself capped by the remaining budget
   * (i.e. shorter than [SINGLE_REQUEST_TIMEOUT]) — only a timeout on a request that got the full
   * [SINGLE_REQUEST_TIMEOUT] indicates a genuinely unresponsive server and propagates unchanged.
   * Any other exception from [graphQlClient] propagates unchanged.
   *
   * `mergeRequest.userPermissions.createNote` is read from the **first** page only and carried
   * into the result regardless of later pages: a merge request with zero discussions returns no
   * notes at all, so per-note permissions cannot answer "can this user comment", and only the
   * envelope-level value on the first (and, for an empty MR, only) page can.
   *
   * Each page's discussions are normalized via [DiscussionDto.toDomain], then notes whose `system`
   * flag is `true` (GitLab's automated activity entries) are dropped, and a discussion left with no
   * notes after that filtering is dropped entirely. The final list — across all pages fetched
   * before any truncation — is sorted by [GitLabDiscussion.createdAt] ascending with a stable sort;
   * notes within a discussion are never reordered, since server order is reply order.
   *
   * A `null` `project` or `null` `project.mergeRequest` in the response means the merge request
   * could not be resolved and throws [GraphQlException]; a `null` `discussions` under a present
   * `mergeRequest` is a merge request with no discussions and is treated as a completed, empty
   * fetch.
   */
  fun getDiscussions(
    connection: ConnectionSnapshot,
    namespaceWithPath: String,
    mrIid: Long,
    deadline: Duration,
    clock: () -> Long = { System.nanoTime() },
    isActive: () -> Boolean = { true },
  ): DiscussionsReadResult {
    val start = clock()
    val discussions = mutableListOf<GitLabDiscussion>()
    var canCreateNote = false
    var cursor: String? = null
    var page = 0

    while (true) {
      if (!isActive()) throw CancellationException("Cancelled during discussions fetch")

      val elapsedNanos = clock() - start
      if (elapsedNanos >= deadline.toNanos()) {
        return buildResult(canCreateNote, discussions, TruncationReason.DEADLINE)
      }

      val timeout = requestTimeout(deadline, elapsedNanos)
      val data = fetchPage(connection, namespaceWithPath, mrIid, cursor, timeout)
        ?: return buildResult(canCreateNote, discussions, TruncationReason.DEADLINE)

      val mergeRequest = data.project?.mergeRequest
        ?: throw GraphQlException(
          hasDataKey = true,
          messages = listOf("Merge request not found in the GraphQL response"),
        )

      page++
      if (page == 1) {
        canCreateNote = mergeRequest.userPermissions?.createNote ?: false
      }

      val discussionConnection = mergeRequest.discussions
      discussions += normalizePage(discussionConnection)

      val pageInfo = discussionConnection?.pageInfo
      if (pageInfo?.hasNextPage != true) {
        return buildResult(canCreateNote, discussions, null)
      }
      val endCursor = pageInfo.endCursor
      if (endCursor.isNullOrBlank()) {
        // The server explicitly said more pages exist but gave nothing to advance with:
        // reporting completion here would silently present a partial list as the whole set.
        return buildResult(canCreateNote, discussions, TruncationReason.MISSING_CURSOR)
      }
      if (page >= MAX_DISCUSSION_PAGES) {
        return buildResult(canCreateNote, discussions, TruncationReason.PAGE_LIMIT)
      }
      cursor = endCursor
    }
  }

  /**
   * Caps a single request's timeout to [SINGLE_REQUEST_TIMEOUT], or the remaining [deadline]
   * budget, whichever is smaller.
   */
  private fun requestTimeout(deadline: Duration, elapsedNanos: Long): Duration {
    val remainingNanos = deadline.toNanos() - elapsedNanos
    return minOf(SINGLE_REQUEST_TIMEOUT, Duration.ofNanos(remainingNanos))
  }

  /**
   * Issues one page request. Returns `null` (meaning: the deadline arrived, stop and return what
   * was fetched) when an [HttpTimeoutException] is caught from a request that was given less than
   * [SINGLE_REQUEST_TIMEOUT] — i.e. one whose timeout was already capped by the remaining budget.
   * An [HttpTimeoutException] from a request given the full [SINGLE_REQUEST_TIMEOUT] is rethrown,
   * as is every other exception.
   */
  private fun fetchPage(
    connection: ConnectionSnapshot,
    namespaceWithPath: String,
    mrIid: Long,
    cursor: String?,
    timeout: Duration,
  ): DiscussionsQueryData? = try {
    graphQlClient.execute(
      GET_MR_DISCUSSIONS_QUERY,
      queryVariables(namespaceWithPath, mrIid, cursor),
      DiscussionsQueryData::class.java,
      connection,
      timeout,
    )
  } catch (e: HttpTimeoutException) {
    if (timeout < SINGLE_REQUEST_TIMEOUT) null else throw e
  }

  /** Normalizes one page's discussion nodes, dropping nulls and discussions left with no notes. */
  private fun normalizePage(discussionConnection: DiscussionConnectionDto?): List<GitLabDiscussion> =
    discussionConnection?.nodes.orEmpty().filterNotNull().mapNotNull { it.toFilteredDomain() }

  /**
   * Sorts [discussions] by [GitLabDiscussion.createdAt] ascending (stable, so equal timestamps
   * keep server order) and logs truncation — counts only, never the query, variables, response
   * body, note text, token, or an exception object.
   */
  private fun buildResult(
    canCreateNote: Boolean,
    discussions: List<GitLabDiscussion>,
    truncation: TruncationReason?,
  ): DiscussionsReadResult {
    val sorted = discussions.sortedBy { it.createdAt }
    if (truncation != null) {
      logger.warn("Discussions fetch truncated: reason=$truncation, discussionsFetched=${sorted.size}")
    }
    return DiscussionsReadResult(canCreateNote, sorted, truncation)
  }
}

/**
 * Normalizes [this] via [DiscussionDto.toDomain] and then drops notes whose `system` flag is
 * `true`, returning `null` (drop the whole discussion) when nothing is left. Filtering happens
 * after normalization, not inside it, so [DiscussionDto.toDomain] can keep preserving `system` for
 * its own, separate tests.
 */
private fun DiscussionDto.toFilteredDomain(): GitLabDiscussion? {
  val discussion = toDomain()
  val nonSystemNotes = discussion.notes.filterNot { it.system }
  if (nonSystemNotes.isEmpty()) return null
  return discussion.copy(notes = nonSystemNotes)
}
