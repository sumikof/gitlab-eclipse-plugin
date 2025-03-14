package com.gitlab.eclipse.authentication

import com.google.gson.annotations.SerializedName

data class GitLabAuthorizationToken(
  @SerializedName("access_token")
  val accessToken: String,

  @SerializedName("refresh_token")
  val refreshToken: String,

  @SerializedName("expires_in")
  val expiresIn: Int,

  @SerializedName("created_at")
  val createdAt: Long,

  // Making the token expired earlier to prevent the scenario where a delayed request might send an expired token to the server
  private val tokenExpirationBufferSeconds: Int = 120,
  val tokenExpirationTimestamp: Long = expiresIn.let { createdAt.plus(it).minus(tokenExpirationBufferSeconds) }
)
