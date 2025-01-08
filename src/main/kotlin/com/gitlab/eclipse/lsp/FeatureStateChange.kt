package com.gitlab.eclipse.lsp

data class FeatureStateChange(
    val featureId: String,
    val engagedChecks: List<FeatureStateChangeCheck>
)
