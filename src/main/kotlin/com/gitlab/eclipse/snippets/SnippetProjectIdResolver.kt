package com.gitlab.eclipse.snippets

import com.gitlab.eclipse.api.ProjectDetailService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.navigation.PathSegmentEncoder

/**
 * Resolves the numeric project id the snippet API needs (design U1).
 *
 * The A-plan URL resolution stops at `namespaceWithPath`, but `POST /projects/{id}/snippets`
 * takes the numeric id or a fully encoded path. The whole namespace path goes through
 * [PathSegmentEncoder.encodeSegment], which encodes `/` as well — `encodePath` preserves `/`
 * and would address a different endpoint. This is the same lookup `CurrentBranchMrLookup`
 * already makes.
 */
class SnippetProjectIdResolver(
  private val projectDetail: ProjectDetailService = service(),
) {
  fun resolve(namespaceWithPath: String): Long =
    projectDetail.getProject(PathSegmentEncoder.encodeSegment(namespaceWithPath)).id
}
