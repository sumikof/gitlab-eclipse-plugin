package com.gitlab.eclipse.authentication

interface TokenProvider {
  fun getToken(): String

  /**
   * Whether a token is available, without refreshing it or making any network call. Safe to call on
   * the UI thread, although it may still read secure storage.
   */
  fun hasToken(): Boolean
}
