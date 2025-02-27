package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.preferences.storage.SecretStorage

class PatProvider : TokenProvider {
  override fun getToken(): String {
    return SecretStorage("gitlab.com").getSecret("personal_access_token").orEmpty()
  }
}
