package com.gitlab.eclipse.api.model

import com.google.gson.annotations.SerializedName

/** Partial GitLab REST project element (only the fields the view needs). */
data class GitLabProject(
  @SerializedName("id") val id: Long,
  @SerializedName("default_branch") val defaultBranch: String? = null,
  /** HTTPS clone url. Nullable: absent from responses that predate the clone commands. */
  @SerializedName("http_url_to_repo") val httpUrlToRepo: String? = null,
)
