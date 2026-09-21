package com.gitlab.eclipse.utils

import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Platform
import org.eclipse.core.runtime.Status
import org.osgi.framework.FrameworkUtil

inline fun <reified T> logger(): ILog = Platform.getLog(FrameworkUtil.getBundle(T::class.java))

/**
 * The gate for [debug] logging — the Eclipse counterpart of the reference extension's
 * `gitlab.debug` setting (design §5.1.1).
 *
 * The reference's setting is a boolean that does exactly one thing that matters here: decide
 * whether `log.debug()` reaches the output at all (`src/common/log.ts:63`). It is **not** the same
 * knob as this plugin's `gitlab.languageServer.logLevel`, which sets the *language server's* level
 * and is sent to it in the configuration payload. The two coexist because they govern different
 * processes.
 *
 * [isEnabled] is a seam with a safe default, following the same pattern as `ClipboardWriter`'s
 * injected hops: it is replaced at startup with a read of the preference store, and a test JVM that
 * never starts Koin still gets a well-defined answer. Reading it per call is deliberate — a change
 * in the preferences takes effect on the next line logged, with no restart.
 */
object DebugLogging {
  /** Replaced at startup by a preference read. Defaults to off, matching the reference default. */
  @Volatile
  var isEnabled: () -> Boolean = { false }
}

/**
 * Logs [message] only while debug logging is on.
 *
 * Eclipse's [IStatus] has no DEBUG severity, so the entry goes out as [IStatus.INFO] carrying a
 * `[debug]` prefix. That is a presentation compromise; the substance is that **with the setting off
 * the [ILog] is never called at all**, so nothing is delivered, filtered or formatted.
 */
fun ILog.debug(message: String) {
  if (!DebugLogging.isEnabled()) return
  log(Status(IStatus.INFO, bundle.symbolicName, "$DEBUG_PREFIX$message"))
}

/**
 * [debug] for a message that costs something to build.
 *
 * The lambda is not invoked while debug logging is off, so a caller may interpolate freely without
 * paying for it in the default configuration.
 */
fun ILog.debug(message: () -> String) {
  if (!DebugLogging.isEnabled()) return
  log(Status(IStatus.INFO, bundle.symbolicName, "$DEBUG_PREFIX${message()}"))
}

private const val DEBUG_PREFIX = "[debug] "
