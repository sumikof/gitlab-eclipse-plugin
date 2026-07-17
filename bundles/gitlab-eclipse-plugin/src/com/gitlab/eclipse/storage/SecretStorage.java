package com.gitlab.eclipse.storage;

import java.net.URI;
import java.net.URISyntaxException;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;

import com.gitlab.eclipse.preferences.PreferenceConstants;
import com.gitlab.eclipse.preferences.PreferenceInitializer;

public class SecretStorage {
  private ISecurePreferences node;
  
  public SecretStorage(String rootURI) {
    node = SecurePreferencesFactory.getDefault()
    		.node("gitlab")
    		.node("hosts")
    		.node(rootURI);
  }

	/** Secret storage for the host of the configured GitLab instance URL. */
	public static SecretStorage forConfiguredInstance() {
		return new SecretStorage(hostOf(
				PreferenceInitializer.PREFERENCE_STORE.getString(PreferenceConstants.GITLAB_INSTANCE_URL)));
	}

	/** Extracts the host from a GitLab instance URL, falling back to gitlab.com. */
	public static String hostOf(String url) {
		if (url != null && !url.isBlank()) {
			try {
				String host = new URI(url.trim()).getHost();
				if (host != null && !host.isBlank()) {
					return host;
				}
			} catch (URISyntaxException e) {
				// fall through to default
			}
		}
		return "gitlab.com";
	}

  public String getSecret(String key) {
    return getSecret(key, null);
  }

  public String getSecret(String key, String def) {
    try {
      return node.get(key, def);
    } catch (StorageException e) {
      e.printStackTrace();
      return null;
    }
  }

	public void putSecret(String key, String value) throws StorageException {
		node.put(key, value, true);
	}
}

