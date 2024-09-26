package com.gitlab.eclipse.lsp;

import java.util.List;

public class WebviewInfo {
	@Override
	public String toString() {
		return "WebviewInfo [id=" + id + ", title=" + title + ", uris=" + uris + "]";
	}

	private String id;
	private String title;
	private List<String> uris;

	public WebviewInfo(String id, String title, List<String> uris) {
		this.id = id;
		this.title = title;
		this.uris = uris;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public List<String> getUris() {
		return uris;
	}

	public void setUris(List<String> uris) {
		this.uris = uris;
	}
}
