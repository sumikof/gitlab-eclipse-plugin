package com.gitlab.eclipse.authentication

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type
import java.time.Instant

const val TOKEN_EXPIRATION_BUFFER_SECONDS: Int = 120

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
  val tokenExpirationTimestamp: Instant = Instant.ofEpochSecond(
    createdAt.plus(expiresIn).minus(TOKEN_EXPIRATION_BUFFER_SECONDS)
  )
)

class GitLabAuthorizationTokenDeserializer : JsonDeserializer<GitLabAuthorizationToken> {
  override fun deserialize(
    json: JsonElement,
    typeOfT: Type,
    context: JsonDeserializationContext
  ): GitLabAuthorizationToken {
    val jsonObject = json.asJsonObject

    val accessToken = jsonObject["access_token"].asString
    val refreshToken = jsonObject["refresh_token"].asString
    val expiresIn = jsonObject["expires_in"].asInt
    val createdAt = jsonObject["created_at"].asLong

    val tokenExpirationTimestamp =
      Instant.ofEpochSecond(createdAt.plus(expiresIn).minus(TOKEN_EXPIRATION_BUFFER_SECONDS))

    return GitLabAuthorizationToken(accessToken, refreshToken, expiresIn, createdAt, tokenExpirationTimestamp)
  }
}
