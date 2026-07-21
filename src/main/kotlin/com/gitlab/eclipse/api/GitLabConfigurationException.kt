package com.gitlab.eclipse.api

/**
 * Thrown for egress/TLS/proxy misconfiguration (bad cert/key/CA files, unsupported
 * key format, mismatched cert/key pairing). The [message] is user-safe — it contains
 * no secrets, key material, file contents, or paths — so it MAY be shown in the UI.
 */
class GitLabConfigurationException(message: String) : RuntimeException(message)
