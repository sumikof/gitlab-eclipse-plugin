package com.gitlab.eclipse.api

import com.gitlab.eclipse.api.model.CiLintResult
import com.gitlab.eclipse.inject.service
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Raw parse target for the `POST /ci/lint` response body (design §8.1/§8.3). Gson constructs this
 * via Unsafe, bypassing the constructor/default-argument evaluation, so any field absent from the
 * JSON is left `null` regardless of a non-null Kotlin type declaration — every field here is
 * therefore nullable, and [CiLintService.validate] normalizes it into the non-null [CiLintResult].
 */
private data class CiLintResponse(
  val valid: Boolean?,
  @SerializedName("merged_yaml") val mergedYaml: String?,
  val errors: List<String>?,
)

/**
 * Validates a `.gitlab-ci.yml` document against a project's CI lint endpoint (design §8.1/§8.3).
 * Thin service in the same style as [JobTraceService]: pure path/body assembly + delegation to
 * [GitLabApiClient.postJson], plus response normalization. No exception handling here — errors
 * from the API client propagate to the caller (classification/audit happens in `runCiLint`).
 */
class CiLintService(private val apiClient: GitLabApiClient = service()) {
  private val gson = Gson()

  fun validate(connection: ConnectionSnapshot, projectId: String, content: String): CiLintResult {
    val body = gson.toJson(mapOf("content" to content))
    val response = apiClient.postJson("/projects/$projectId/ci/lint", body, connection)
    val parsed = gson.fromJson(response, CiLintResponse::class.java)
    return CiLintResult(
      valid = parsed.valid ?: false,
      mergedYaml = parsed.mergedYaml,
      errors = parsed.errors ?: emptyList(),
    )
  }
}
