package com.gitlab.eclipse.lsp

/**
 * The identity of one language server connection. It carries no state: only reference equality is
 * meaningful. One instance is created per connection and lives exactly as long as that connection.
 */
class LanguageServerSession

/**
 * The proxy of a language server connection together with the identity of that connection.
 *
 * Published as one immutable value so that a reader can never observe a new proxy paired with the
 * session of the connection it replaced.
 */
data class LanguageServerHandle(
  val proxy: GitLabLanguageServer,
  val session: LanguageServerSession,
)
