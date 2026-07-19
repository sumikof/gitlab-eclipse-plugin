package com.gitlab.eclipse.api.model

import com.google.gson.annotations.SerializedName

/** Partial GitLab REST `/issues` element (only the fields the view needs). */
data class GitLabIssue(
  @SerializedName("id") val id: Long,
  @SerializedName("iid") val iid: Long,
  @SerializedName("title") val title: String,
  @SerializedName("web_url") val webUrl: String,
  @SerializedName("state") val state: String,
  @SerializedName("references") val references: Reference?,
) {
  data class Reference(@SerializedName("full") val full: String?)
}
