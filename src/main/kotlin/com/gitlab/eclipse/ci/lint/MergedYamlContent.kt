package com.gitlab.eclipse.ci.lint

/**
 * Mutable holder for the current merged-yaml text, shared per [MergedYamlKey].
 *
 * Writes happen on the UI thread only; `@Volatile` is defensive so any reader
 * observes the latest text.
 */
class MergedYamlContent(@Volatile var text: String)
