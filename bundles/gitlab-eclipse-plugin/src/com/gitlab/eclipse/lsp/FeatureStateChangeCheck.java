package com.gitlab.eclipse.lsp;

public class FeatureStateChangeCheck {
	private String checkId;
	private String details;

	public FeatureStateChangeCheck(String checkId, String details) {
		this.checkId = checkId;
		this.details = details;
	}

	public String getCheckId() {
		return checkId;
	}

	public String getDetails() {
		return details;
	}
	
	public void setDetails(String details) {
		this.details = details;
	}

	public void setCheckId(String checkId) {
		this.checkId = checkId;
	}
}
