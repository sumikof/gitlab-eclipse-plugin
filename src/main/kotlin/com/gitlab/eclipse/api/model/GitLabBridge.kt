package com.gitlab.eclipse.api.model

import com.google.gson.annotations.SerializedName

/**
 * Partial GitLab REST `/pipelines/:id/bridges` element: a job that triggers a downstream
 * (child or multi-project) pipeline. `downstream_pipeline` is `null` (or, on old instances,
 * possibly absent) while the bridge has not started one; both read back as `null` here.
 * Its `project_id` / `source` may be missing on old instances, so callers must treat
 * those as optional.
 */
data class GitLabBridge(
  @SerializedName("id") val id: Long,
  @SerializedName("name") val name: String? = null,
  @SerializedName("stage") val stage: String? = null,
  @SerializedName("status") val status: String? = null,
  @SerializedName("web_url") val webUrl: String? = null,
  @SerializedName("allow_failure") val allowFailure: Boolean? = null,
  @SerializedName("downstream_pipeline") val downstreamPipeline: GitLabPipeline? = null,
)
