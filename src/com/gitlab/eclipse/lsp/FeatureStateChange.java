package com.gitlab.eclipse.lsp;

import java.util.List;

public class FeatureStateChange {
	private String featureId;

	private List<FeatureStateChangeCheck> engagedChecks;
	
	public FeatureStateChange(String featureId, List<FeatureStateChangeCheck> engagedChecks) {
		this.featureId = featureId;
		this.engagedChecks = engagedChecks;
	}

	public String getFeatureId() {
		return featureId;
	}

	public List<FeatureStateChangeCheck> getEngagedChecks() {
		return engagedChecks;
	}

	public void setFeatureId(String featureId) {
		this.featureId = featureId;
	}

	public void setEngagedChecks(List<FeatureStateChangeCheck> engagedChecks) {
		this.engagedChecks = engagedChecks;
	}
}
