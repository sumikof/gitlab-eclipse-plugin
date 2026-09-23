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
 *
 * @property connectionEpoch the connection epoch of this connection, copied from its
 *   `GitLabLanguageServerClient` rather than read from the registry, so a sender that stamps a
 *   request with it uses the very value the receiving side compares responses against. Senders take
 *   the proxy and this epoch from **one** read of the wrapper's current snapshot: two separate reads
 *   can straddle a reconnect and pair a live proxy with a dead epoch, or the reverse. No default on
 *   purpose — a default would let a construction site forget it silently.
 */
data class LanguageServerHandle(
  val proxy: GitLabLanguageServer,
  val session: LanguageServerSession,
  val connectionEpoch: Long,
)
