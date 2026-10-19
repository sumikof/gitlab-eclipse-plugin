package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.views.sidebar.ChangeType
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.eclipse.compare.internal.CompareUIPlugin

internal val FIXTURE_BASE = "a".repeat(40)
internal val FIXTURE_HEAD = "b".repeat(40)

/** A validated-shape request for the Compare adapter specs. */
internal fun compareRequest(
  changeType: ChangeType = ChangeType.MODIFIED,
  oldPath: String? = "src/Old.kt",
  newPath: String? = "src/New.kt",
  head: String = FIXTURE_HEAD,
) = MrFileDiffRequest(
  instanceUrl = "https://gl.example",
  authFingerprint = "fp",
  projectId = 42L,
  mrIid = 7L,
  namespaceWithPath = "g/p",
  baseSha = FIXTURE_BASE,
  headSha = head,
  oldPath = oldPath,
  newPath = newPath,
  changeType = changeType,
  aMode = "100644",
  bMode = "100644",
)

/** Loaded content for [request]: each side present in [MrDiffSides] gets [leftBytes] / [rightBytes]. */
internal fun compareContent(
  request: MrFileDiffRequest = compareRequest(),
  leftBytes: ByteArray = "left\n".toByteArray(),
  rightBytes: ByteArray = "right\n".toByteArray(),
): MrFileDiffContent {
  val sides = MrDiffSides.of(request)
  return MrFileDiffContent(
    request = request,
    sides = sides,
    left = sides.left?.let { ClassifiedContent(leftBytes, "kt", "UTF-8") },
    right = sides.right?.let { ClassifiedContent(rightBytes, "kt", "UTF-8") },
  )
}

/**
 * Lets Compare's classes initialize without the Compare plugin: `CompareEditorInput`'s static
 * init reads `ICompareUIConstants`, whose constants call `CompareUIPlugin.getPluginId()`, which
 * NPEs when the plugin is not running. Call from `beforeSpec`; pair with [releaseHeadlessCompare].
 */
internal fun stubHeadlessCompare() {
  mockkStatic(CompareUIPlugin::class)
  every { CompareUIPlugin.getPluginId() } returns "org.eclipse.compare"
}

internal fun releaseHeadlessCompare() {
  unmockkStatic(CompareUIPlugin::class)
}
