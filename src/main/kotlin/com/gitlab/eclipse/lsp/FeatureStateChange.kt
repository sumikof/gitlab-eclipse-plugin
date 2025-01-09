package com.gitlab.eclipse.lsp

data class FeatureStateChangeCheck(val checkId: String, val details: String)

data class FeatureStateChange(
  val featureId: String,
  val engagedChecks: List<FeatureStateChangeCheck>
)
