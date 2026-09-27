package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.lsp.LanguageServerSession

/**
 * The three-valued authentication state kept by [AuthenticationSourceProvider] (design §12.1).
 *
 * [UNKNOWN] is only ever written by [AuthenticationSourceProvider.reset] as a tombstone; an update
 * from the language server always resolves to one of the other two.
 */
enum class AuthState { UNKNOWN, AUTHENTICATED, SIGN_IN_REQUIRED }

/**
 * An authentication state together with the connection that reported it and the
 * `AuthenticationStateService` generation it was settled in.
 *
 * Only reference identity of [session] is meaningful. [generation] is taken verbatim from the
 * service: the provider neither creates nor re-reads generations, it only compares them.
 */
data class SessionAuthState(val session: LanguageServerSession, val generation: Long, val state: AuthState)
