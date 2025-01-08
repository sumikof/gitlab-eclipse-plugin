package com.gitlab.eclipse.utils

import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.runtime.IPath

val IFile.relativePath
  get(): IPath? {
    val workspaceLocation = ResourcesPlugin.getWorkspace().root.location
    return location.makeRelativeTo(workspaceLocation)
  }
