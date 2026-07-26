package com.gitlab.eclipse.api.model

import com.google.gson.annotations.SerializedName

/** Partial GitLab REST user element (only the fields the view needs). */
data class GitLabUser(
  @SerializedName("id") val id: Long,
  @SerializedName("username") val username: String,
)
