package com.gitlab.eclipse.api

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.logger
import java.time.Duration

/** Raw parse target for the GraphQL `Snippet.blobs` nodes the list query selects. */
internal data class SnippetBlobDto(val name: String?, val path: String?, val rawPath: String?)

internal data class SnippetBlobConnectionDto(val nodes: List<SnippetBlobDto?>?)

internal data class SnippetSummaryDto(
  val id: String?,
  val title: String?,
  val description: String?,
  val blobs: SnippetBlobConnectionDto?,
)

internal data class SnippetPageInfoDto(val hasNextPage: Boolean?)

internal data class SnippetConnectionDto(
  val pageInfo: SnippetPageInfoDto?,
  val nodes: List<SnippetSummaryDto?>?,
)

internal data class SnippetsProjectDto(val snippets: SnippetConnectionDto?)

/** `data` payload of the list query. */
internal data class SnippetsQueryData(val project: SnippetsProjectDto?)

internal data class SnippetContentBlobDto(val path: String?, val rawPlainData: String?)

internal data class SnippetContentBlobConnectionDto(val nodes: List<SnippetContentBlobDto?>?)

internal data class SnippetContentDto(val blobs: SnippetContentBlobConnectionDto?)

internal data class SnippetContentConnectionDto(val nodes: List<SnippetContentDto?>?)

/** `data` payload of the content query. */
internal data class SnippetContentQueryData(val snippets: SnippetContentConnectionDto?)

/** One file inside a snippet, as the picker shows it. */
data class SnippetBlob(val name: String, val path: String)

/** One snippet, as the picker shows it. */
data class SnippetSummary(
  val id: String,
  val title: String,
  val description: String,
  val blobs: List<SnippetBlob>,
)

/**
 * The first page of a project's snippets. [hasNextPage] is carried so the UI can say the list was
 * truncated instead of silently showing a partial set (design §11).
 */
data class SnippetListPage(val snippets: List<SnippetSummary>, val hasNextPage: Boolean)

/** Reads project snippets over GraphQL (design F2 / F4). */
class SnippetQueryService(
  private val apiClient: GitLabApiClient = service(),
  private val graphQlClient: GitLabGraphQlClient = service(),
) {
  private val logger by lazy { logger<SnippetQueryService>() }

  /**
   * First page of the project's snippets, or null when the connection gate rejected the instance.
   *
   * Only the first page is fetched (design §11): [SnippetListPage.hasNextPage] lets the caller
   * disclose the truncation rather than pretend the list is complete.
   */
  fun listSnippets(
    namespaceWithPath: String,
    acceptInstanceUrl: (String) -> Boolean,
  ): SnippetListPage? {
    val connection = apiClient.captureConnectionIf(acceptInstanceUrl) ?: run {
      logger.info("Snippet listing skipped: the configured instance did not match.")
      return null
    }
    val data = graphQlClient.execute(
      GET_SNIPPETS_QUERY,
      mapOf("namespaceWithPath" to namespaceWithPath, "afterCursor" to null),
      SnippetsQueryData::class.java,
      connection,
      REQUEST_TIMEOUT,
    )
    val connectionDto = data.project?.snippets
    val snippets = connectionDto?.nodes.orEmpty().filterNotNull().mapNotNull { it.toDomain() }
    return SnippetListPage(snippets, connectionDto?.pageInfo?.hasNextPage == true)
  }

  /**
   * Raw text of the blob at [blobPath] inside snippet [snippetId], or null when the gate rejected
   * the instance or no blob carries that path.
   */
  fun fetchBlobContent(
    snippetId: String,
    blobPath: String,
    acceptInstanceUrl: (String) -> Boolean,
  ): String? {
    val connection = apiClient.captureConnectionIf(acceptInstanceUrl) ?: run {
      logger.info("Snippet content fetch skipped: the configured instance did not match.")
      return null
    }
    val data = graphQlClient.execute(
      GET_SNIPPET_CONTENT_QUERY,
      mapOf("snippetId" to snippetId),
      SnippetContentQueryData::class.java,
      connection,
      REQUEST_TIMEOUT,
    )
    return data.snippets?.nodes.orEmpty().filterNotNull()
      .flatMap { it.blobs?.nodes.orEmpty().filterNotNull() }
      .firstOrNull { it.path == blobPath }
      ?.rawPlainData
  }

  /** Drops snippets the server left without an id — they cannot be fetched afterwards. */
  private fun SnippetSummaryDto.toDomain(): SnippetSummary? {
    val snippetId = id ?: return null
    val files = blobs?.nodes.orEmpty().filterNotNull().mapNotNull { blobDto ->
      val path = blobDto.path ?: return@mapNotNull null
      SnippetBlob(name = blobDto.name ?: path, path = path)
    }
    return SnippetSummary(snippetId, title.orEmpty(), description.orEmpty(), files)
  }

  private companion object {
    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

    /** Verbatim from the reference extension (`graphql/get_snippets.ts`). */
    private val GET_SNIPPETS_QUERY = """
      query GetSnippets(${'$'}namespaceWithPath: ID!, ${'$'}afterCursor: String) {
        project(fullPath: ${'$'}namespaceWithPath) {
          id
          snippets(after: ${'$'}afterCursor) {
            pageInfo { hasNextPage endCursor }
            nodes {
              id
              title
              description
              blobs { nodes { name path rawPath } }
            }
          }
        }
      }
    """.trimIndent()

    /** Verbatim from the reference extension (`graphql/get_snippet_content.ts`). */
    private val GET_SNIPPET_CONTENT_QUERY = """
      query GetSnippetContent(${'$'}snippetId: SnippetID!) {
        snippets(ids: [${'$'}snippetId]) {
          nodes { blobs { nodes { path rawPlainData } } }
        }
      }
    """.trimIndent()
  }
}
