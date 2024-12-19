package com.gitlab.eclipse.lsp

class FeatureStateChange(var featureId: String, engagedChecks: List<FeatureStateChangeCheck>) {
    private var engagedChecks: List<FeatureStateChangeCheck>

    init {
        this.engagedChecks = engagedChecks
    }

    fun getEngagedChecks(): List<FeatureStateChangeCheck> {
        return engagedChecks
    }

    fun setEngagedChecks(engagedChecks: List<FeatureStateChangeCheck>) {
        this.engagedChecks = engagedChecks
    }
}