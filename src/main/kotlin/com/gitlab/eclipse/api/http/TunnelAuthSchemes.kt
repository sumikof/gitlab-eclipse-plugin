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
 *
 * If the system property is UNSET, it is left untouched: java.net.http falls back (via
 * `sun.net.NetProperties`, which we cannot portably read here) to the JDK default or the
 * administrator's `conf/net.properties`, and overwriting it would silently wipe a
 * non-Basic restriction such as NTLM. In that case, reliable Basic-over-tunnel requires
 * the eclipse.ini VM arg above.
 */
fun relaxTunnelBasicAuthScheme() {
  val key = "jdk.http.auth.tunneling.disabledSchemes"
  // Only edit an explicitly-set system property. When it is unset, the effective policy
  // comes from the JDK default or the administrator's conf/net.properties (which java.net.http
  // reads via sun.net.NetProperties, and which we cannot portably read here) — overwriting it
  // would silently wipe a non-Basic restriction such as NTLM. So we leave it untouched in that
  // case; reliable Basic-over-tunnel then requires the eclipse.ini VM arg
  // -Djdk.http.auth.tunneling.disabledSchemes= .
  val current = System.getProperty(key) ?: return
  val remaining = current.split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.equals("Basic", ignoreCase = true) }
  System.setProperty(key, remaining.joinToString(","))
}
