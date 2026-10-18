package com.gitlab.eclipse.authentication

/**
 * A token source scoped to GitLab instances (#115): a token is only ever answered for the instance it belongs to,
 * so the caller passes the URL it will send the token to.
 */
interface TokenProvider {
  fun getToken(instanceUrl: String): String

  /**
   * Whether a token is available for [instanceUrl], without refreshing it or making any network call. Safe to call
   * on the UI thread, although it may still read secure storage.
   */
  fun hasToken(instanceUrl: String): Boolean
}
