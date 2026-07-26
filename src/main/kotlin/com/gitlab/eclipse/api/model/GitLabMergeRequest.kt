package com.gitlab.eclipse.api.model

import com.google.gson.annotations.SerializedName

/** Partial GitLab REST `/merge_requests` element (only the fields the view needs). */
data class GitLabMergeRequest(
  @SerializedName("id") val id: Long,
  @SerializedName("iid") val iid: Long,
  @SerializedName("title") val title: String,
  @SerializedName("project_id") val projectId: Long,
  @SerializedName("web_url") val webUrl: String,
  @SerializedName("state") val state: String,
  @SerializedName("draft") val draft: Boolean = false,
  @SerializedName("source_project_id") val sourceProjectId: Long? = null,
  @SerializedName("target_project_id") val targetProjectId: Long? = null,
  @SerializedName("source_branch") val sourceBranch: String? = null,
  @SerializedName("sha") val sha: String? = null,
  @SerializedName("updated_at") val updatedAt: String? = null,
  @SerializedName("references") val references: Reference? = null,
) {
  data class Reference(@SerializedName("full") val full: String?)
}
