package com.gitlab.eclipse.lsp

data class FeatureStateChange(
  val featureId: String,
  val allChecks: List<FeatureStateChangeCheck>? = null
)

data class FeatureStateChangeCheck(
  val checkId: String,
  val engaged: Boolean,
  val details: String? = null,
)
