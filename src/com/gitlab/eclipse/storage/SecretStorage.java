package com.gitlab.eclipse.storage;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;

public class SecretStorage {
  private ISecurePreferences node;
  
  public SecretStorage(String rootURI) {
    node = SecurePreferencesFactory.getDefault()
    		.node("gitlab")
    		.node("hosts")
    		.node(rootURI);
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

