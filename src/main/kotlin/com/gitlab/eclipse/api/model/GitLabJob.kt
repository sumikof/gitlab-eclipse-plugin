package com.gitlab.eclipse.api.model

import com.google.gson.annotations.SerializedName

/** Partial GitLab REST `/jobs` element (only the fields the view needs). */
data class GitLabJob(
  @SerializedName("id") val id: Long,
  @SerializedName("name") val name: String? = null,
  @SerializedName("status") val status: String? = null,
  @SerializedName("stage") val stage: String? = null,
  @SerializedName("web_url") val webUrl: String? = null,
  @SerializedName("allow_failure") val allowFailure: Boolean? = null,
)
