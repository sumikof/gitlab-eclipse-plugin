package com.gitlab.eclipse.preferences.storage

import io.mockk.every
import io.mockk.mockk
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle

/**
 * Routes the plugin's log to a recorder, so a test can assert what reached it. Requires
 * `LoggingKotestExtension` (it statically mocks [Platform]); call [install] in the test after that
 * extension's `beforeEach`.
 */
class CapturedLog {
  /** Every message logged, and the message and class of every throwable attached to an entry. */
  val lines = mutableListOf<String>()

  fun install() {
    val log = mockk<ILog>(relaxed = true)
    every { log.info(any()) } answers { lines += firstArg<String>() }
    every { log.warn(any()) } answers { lines += firstArg<String>() }
    every { log.error(any()) } answers { lines += firstArg<String>() }
    every { log.info(any(), any()) } answers { record(firstArg(), secondArg()) }
    every { log.warn(any(), any()) } answers { record(firstArg(), secondArg()) }
    every { log.error(any(), any()) } answers { record(firstArg(), secondArg()) }
    every { log.log(any()) } answers {
      val status = firstArg<IStatus>()
      record(status.message, status.exception)
    }
    every { Platform.getLog(any<Bundle>()) } returns log
    every { Platform.getLog(any<Class<*>>()) } returns log
  }

  private fun record(message: String?, throwable: Throwable?) {
    lines += message.orEmpty()
    throwable?.let { lines += "${it::class.java.name}: ${it.message}" }
  }

  /** Whether [fragment] appears anywhere in what was logged. */
  fun contains(fragment: String): Boolean = lines.any { it.contains(fragment) }
}
