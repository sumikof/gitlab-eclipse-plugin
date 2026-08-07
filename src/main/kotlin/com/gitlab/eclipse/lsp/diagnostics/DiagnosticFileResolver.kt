package com.gitlab.eclipse.lsp.diagnostics

import org.eclipse.core.filesystem.URIUtil
import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.ResourcesPlugin

/**
 * Maps a normalised absolute path onto the workspace files that show it.
 *
 * Uses `findFilesForLocationURI` rather than `getFileForLocation`: the same file on disk can be
 * linked into several projects and every one of them has to display the diagnostics.
 *
 * The workspace is only guaranteed to answer while it is open, so both the URI conversion and the
 * lookup are treated as best effort: a location we cannot resolve simply has no markers.
 */
object DiagnosticFileResolver {
  fun resolve(normalizedPath: String): List<IFile> {
    val uri = runCatching { URIUtil.toURI(normalizedPath) }.getOrNull() ?: return emptyList()
    val files = runCatching { ResourcesPlugin.getWorkspace().root.findFilesForLocationURI(uri) }
      .getOrNull() ?: return emptyList()
    return files.filter { it.exists() }
  }
}
