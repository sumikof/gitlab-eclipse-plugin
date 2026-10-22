package com.gitlab.eclipse.views.sidebar

import com.gitlab.eclipse.api.model.GitLabBridge
import com.gitlab.eclipse.api.model.GitLabPipeline

// Shared fixtures for the CI-05/CI-06 pipeline-tree tests (SidebarViewModelTest,
// SidebarViewModelDownstreamTest and the Task 5 downstream-loading tests).

/** A bridge (trigger job) in [stage], optionally with the [downstream] pipeline it started. */
internal fun bridge(
  id: Long,
  name: String? = "bridge$id",
  stage: String? = "build",
  status: String? = "success",
  allowFailure: Boolean? = null,
  downstream: GitLabPipeline? = null,
) = GitLabBridge(id, name, stage, status, "https://gitlab.example.com/p/-/jobs/$id", allowFailure, downstream)

/** A bridge's `downstream_pipeline` (a child pipeline of project 42 by default). */
internal fun downstream(
  id: Long,
  status: String? = "running",
  projectId: Long? = 42L,
  source: String? = "parent_pipeline",
) =
  GitLabPipeline(
    id = id,
    status = status,
    projectId = projectId,
    source = source,
    webUrl = "https://gitlab.example.com/d/-/pipelines/$id",
  )

/** Stage names with their children's names (the label part before the status), in tree order. */
internal fun shape(children: List<SidebarNode>) =
  children.filterIsInstance<StageNode>().map { stage ->
    stage.name to stage.children.map { it.label.substringBefore(" · ") }
  }

/** The job / bridge ids of each stage, in tree order. */
internal fun stageIds(children: List<SidebarNode>) =
  children.filterIsInstance<StageNode>().map { stage ->
    stage.children.map { (it as? JobNode)?.job?.id ?: (it as BridgeNode).bridge.id }
  }
