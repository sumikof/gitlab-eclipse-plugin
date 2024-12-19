package com.gitlab.eclipse.lsp

import org.eclipse.core.runtime.FileLocator
import org.eclipse.core.runtime.Path
import org.eclipse.core.runtime.Platform
import org.osgi.framework.FrameworkUtil

class InstallLanguageServer {
    fun install() {
        try {
            val bundle = Platform.getBundle("com.gitlab.eclipse.gitlab-language-server.cocoa.macosx.aarch64")
            val bin = FileLocator.find(bundle, Path.fromOSString("/bin/gitlab-lsp"))
            val destination = bundle.getDataFile("gitlab-lsp")
            if (destination.createNewFile()) {
                destination.writeText(bin.readText())
            }
            destination.setExecutable(true)
        } catch (e: Throwable) {
            Platform.getLog(FrameworkUtil.getBundle(javaClass)).error("threw an error", e)
        }
    }
}