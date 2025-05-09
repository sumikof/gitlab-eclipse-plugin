package com.gitlab.eclipse.preferences.healthcheck

data class FeatureStateParams(
  val featureId: String,
  val engagedChecks: List<FeatureStateCheckParams>
)
