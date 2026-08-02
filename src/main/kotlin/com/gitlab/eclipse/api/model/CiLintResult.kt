package com.gitlab.eclipse.api.model

/**
 * Normalized result of `POST /ci/lint` (design §8.1/§8.3). Unlike the raw parse DTO, every field
 * here is guaranteed non-null: a missing `valid` defaults to `false`, missing `errors` to an empty
 * list, so callers never need to null-check a lint result.
 */
data class CiLintResult(
  val valid: Boolean,
  val mergedYaml: String?,
  val errors: List<String>,
)
