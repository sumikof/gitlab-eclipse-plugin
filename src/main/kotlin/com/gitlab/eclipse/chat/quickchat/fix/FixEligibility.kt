package com.gitlab.eclipse.chat.quickchat.fix

import org.eclipse.core.resources.IMarker

/** What [isFixEligible] needs from a marker, read lazily (the cheap check runs first). */
data class MarkerFacts(val exists: Boolean, val isProblem: Boolean, val severity: Int, val onFile: Boolean)

/**
 * Design §5.1 / L2: Duo Chat enabled, then an existing problem marker on a file with severity
 * ERROR or WARNING. [chatEnabled] is asked first; [facts] only when it is true. Any exception → false
 * (Problems calls hasResolutions per row on the UI thread; MarkerHelpRegistry logs generator exceptions).
 */
fun isFixEligible(chatEnabled: () -> Boolean, facts: () -> MarkerFacts): Boolean =
  try {
    chatEnabled() && facts().let {
      it.exists && it.isProblem && it.onFile &&
        (it.severity == IMarker.SEVERITY_ERROR || it.severity == IMarker.SEVERITY_WARNING)
    }
  } catch (_: Exception) {
    false
  }
