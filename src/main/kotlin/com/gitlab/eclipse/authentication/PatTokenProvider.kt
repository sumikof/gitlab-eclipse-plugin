package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.preferences.storage.SecretStorage

class PatTokenProvider : TokenProvider {
  override fun getToken(): String {
    return SecretStorage("gitlab.com").getSecret("personal_access_token").orEmpty()
  }
}
