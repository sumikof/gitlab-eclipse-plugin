package com.gitlab.eclipse.api.model

import com.google.gson.annotations.SerializedName

/** Partial GitLab REST `/pipelines` element (only the fields the view needs). */
data class GitLabPipeline(
  @SerializedName("id") val id: Long,
  @SerializedName("iid") val iid: Long? = null,
  @SerializedName("project_id") val projectId: Long? = null,
  @SerializedName("status") val status: String? = null,
  @SerializedName("ref") val ref: String? = null,
  @SerializedName("sha") val sha: String? = null,
  @SerializedName("web_url") val webUrl: String? = null,
  @SerializedName("source") val source: String? = null,
  @SerializedName("created_at") val createdAt: String? = null,
  @SerializedName("updated_at") val updatedAt: String? = null,
)
