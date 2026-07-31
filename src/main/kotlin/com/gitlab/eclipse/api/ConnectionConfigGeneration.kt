package com.gitlab.eclipse.api

import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic generation for the egress connection config (instance URL + credential). Because the URL
 * (preference store) and the token (secure storage) are written as two separate operations, a reader that
 * only re-reads the values cannot tell a torn `(new URL, old token)` intermediate from a settled pair.
 * Any code that changes the instance URL and/or the auth account MUST wrap those writes in
 * [beginUpdate]/[endUpdate] (odd = update in progress, even = stable); [GitLabApiClient.captureConnection]
 * accepts a snapshot only when the generation was even and unchanged across its reads.
 */
object ConnectionConfigGeneration {
  private val counter = AtomicLong(0)
  val generation: Long get() = counter.get()
  fun beginUpdate() { counter.incrementAndGet() } // -> odd
  fun endUpdate() { counter.incrementAndGet() } // -> even
}
