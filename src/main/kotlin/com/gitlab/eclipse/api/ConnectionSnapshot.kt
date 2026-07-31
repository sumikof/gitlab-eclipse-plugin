package com.gitlab.eclipse.api

/**
 * An atomically-captured view of the egress connection (target instance + credential) used to
 * pin a single READ or WRITE operation so a concurrent gitlab.url / auth change cannot misroute
 * it or leak the credential to a different instance. Transient; never persisted, never attached
 * to sidebar nodes (nodes carry only the non-secret [instanceUrl] + [authFingerprint]).
 */
data class ConnectionSnapshot(
  val instanceUrl: String,
  val token: String,
  val authFingerprint: String,
  val configGeneration: Long,
)
