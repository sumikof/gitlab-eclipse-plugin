package com.gitlab.eclipse.mergerequests

/**
 * Resolves the "effective ref" of a [CurrentBranch] against a repository's remote name (Phase 3
 * §8.3, extracted for reuse by the pipeline lookup in Phase 4). The tracking branch is only
 * trustworthy when it points at the SAME remote as [remoteName]; a tracking branch on a different
 * remote (or no tracking at all) means the local short name is the right thing to use. A detached
 * HEAD (no local name) resolves to null.
 */
object EffectiveRef {
  fun resolve(branch: CurrentBranch, remoteName: String): String? =
    branch.trackingBranch?.takeIf { branch.upstreamRemote == remoteName } ?: branch.name
}
