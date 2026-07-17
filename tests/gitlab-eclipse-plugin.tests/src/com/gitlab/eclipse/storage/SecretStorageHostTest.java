package com.gitlab.eclipse.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SecretStorageHostTest {
	@Test
	void derivesHostFromUrl() {
		assertEquals("gitlab.example.com", SecretStorage.hostOf("https://gitlab.example.com"));
		assertEquals("gitlab.example.com", SecretStorage.hostOf("https://gitlab.example.com:8443/group"));
		assertEquals("gitlab.example.com", SecretStorage.hostOf(" https://gitlab.example.com/ "));
	}

	@Test
	void fallsBackToGitlabCom() {
		assertEquals("gitlab.com", SecretStorage.hostOf(null));
		assertEquals("gitlab.com", SecretStorage.hostOf(""));
		assertEquals("gitlab.com", SecretStorage.hostOf("not a url"));
		assertEquals("gitlab.com", SecretStorage.hostOf("/just/a/path"));
	}
}
