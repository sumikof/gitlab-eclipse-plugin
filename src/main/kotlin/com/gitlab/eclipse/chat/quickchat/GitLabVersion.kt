package com.gitlab.eclipse.chat.quickchat

/**
 * A GitLab instance's (major, minor) version, extracted from `Metadata.version` (design §8.1).
 *
 * Only major/minor are kept: Quick Chat's version gate (design §6.4 confirmed fact 1, §9.2.2 step
 * 3) only ever compares against 17.10, so the patch/pre-release suffix carries no decision.
 */
data class GitLabVersion(val major: Int, val minor: Int) {
  /** Whether this version is at least 17.10, the minimum for Quick Chat (design §9.2.2 step 3). */
  fun supportsQuickChat(): Boolean =
    major > MIN_MAJOR || (major == MIN_MAJOR && minor >= MIN_MINOR)

  companion object {
    private const val MIN_MAJOR = 17
    private const val MIN_MINOR = 10

    /**
     * Parses strings like `"17.10.0"`, `"17.10.0-pre"`, `"18.2.1-ee"`, or `" 17.10 "` into a
     * [GitLabVersion]. Returns `null` for `null` or unparsable input; a `null` result means
     * "unknown", and callers continue as if the server were new enough (design §9.2.2 step 3),
     * matching the reference implementation's behavior for a version it cannot interpret.
     */
    fun parse(version: String?): GitLabVersion? {
      val trimmed = version?.trim()
      if (trimmed.isNullOrEmpty()) return null

      val segments = trimmed.split(".")
      if (segments.size < 2) return null

      val major = segments[0].toIntOrNull() ?: return null
      // Keep only the leading digits of the minor segment: a suffix like "-pre" or "-ee" may be
      // appended directly to it in some formats (e.g. "17.10-pre") rather than to a third segment.
      val minorDigits = segments[1].takeWhile { it.isDigit() }
      val minor = minorDigits.toIntOrNull() ?: return null

      return GitLabVersion(major, minor)
    }
  }
}
