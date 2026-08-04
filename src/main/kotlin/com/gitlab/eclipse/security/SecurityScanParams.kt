package com.gitlab.eclipse.security

/**
 * What made the scan run.
 *
 * The two triggers are deliberately not interchangeable: a command is something the user asked for
 * and therefore gets feedback and a tracked waiter, while a save is background work that must stay
 * silent (design §10.2). Anything that branches on the trigger branches on this enum, never on an
 * ad hoc boolean.
 */
enum class SecurityScanSource(
  /** Value put on the wire; the language server distinguishes the two triggers by this string. */
  val wireValue: String,
) {
  COMMAND("command"),
  SAVE("save"),
}

/**
 * Payload of `$/gitlab/security/remoteSecurityScan`.
 *
 * [documentUri] is the URI as the rest of the plugin sends it to the language server
 * (`file:/abs/path`), not the normalised comparison key: the server resolves it itself and echoes
 * back its own spelling, which is why responses are matched on a normalised path instead.
 */
data class SecurityScanParams(val documentUri: String, val source: String)
