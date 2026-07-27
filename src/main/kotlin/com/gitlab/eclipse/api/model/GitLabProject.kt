package com.gitlab.eclipse.api.model

import com.google.gson.annotations.SerializedName

/** Partial GitLab REST project element (only the fields the view needs). */
data class GitLabProject(
  @SerializedName("id") val id: Long,
  @SerializedName("default_branch") val defaultBranch: String? = null,
)
