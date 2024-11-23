package com.gitlab.eclipse.storage

import org.eclipse.equinox.security.storage.ISecurePreferences
import org.eclipse.equinox.security.storage.SecurePreferencesFactory
import org.eclipse.osgi.storage.Storage.StorageException

class SecretStorage(rootURI: String?) {
    private val node: ISecurePreferences = SecurePreferencesFactory.getDefault()
        .node("gitlab")
        .node("hosts")
        .node(rootURI)

    fun getSecret(key: String?): String? {
        return getSecret(key, null)
    }

    fun getSecret(key: String?, def: String?): String? {
        try {
            return node.get(key, def)
        } catch (e: StorageException) {
            e.printStackTrace()
            return null
        }
    }

    @Throws(StorageException::class)
    fun putSecret(key: String?, value: String?) {
        node.put(key, value, true)
    }
}

