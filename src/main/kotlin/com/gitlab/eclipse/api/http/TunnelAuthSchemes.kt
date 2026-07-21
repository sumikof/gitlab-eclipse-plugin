package com.gitlab.eclipse.api.http

/**
 * Removes only the `Basic` token from `jdk.http.auth.tunneling.disabledSchemes`, so
 * Basic proxy authentication works over an HTTPS CONNECT tunnel while preserving any
 * other admin-configured disabled schemes (never a blanket empty-string overwrite).
 *
 * CAVEAT: java.net.http reads this property ONCE, in `java.net.http.common.Utils`'s
 * static initializer. Called from the plugin Activator it is best-effort — if another
 * component in the same JVM initialized java.net.http first, it has no effect. Reliable
 * activation requires the eclipse.ini VM arg `-Djdk.http.auth.tunneling.disabledSchemes=`.
 */
fun relaxTunnelBasicAuthScheme() {
  val key = "jdk.http.auth.tunneling.disabledSchemes"
  // Unset means the JDK default disables Basic, so treat unset as "Basic".
  val current = System.getProperty(key) ?: "Basic"
  val remaining = current.split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.equals("Basic", ignoreCase = true) }
  System.setProperty(key, remaining.joinToString(","))
}
