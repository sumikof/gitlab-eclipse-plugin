package com.gitlab.eclipse.lsp

import org.eclipse.core.runtime.FileLocator
import org.eclipse.core.runtime.Path
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle
import org.osgi.framework.FrameworkUtil
import java.net.URL

class InstallLanguageServer {
    fun install() {
        try {
            val bundle: Bundle? = Platform.getBundle("com.gitlab.eclipse.gitlab-language-server.cocoa.macosx.aarch64")
            if (bundle != null) {
                val bin: URL? = FileLocator.find(bundle, Path.fromOSString("/bin/gitlab-lsp"))
                bin?.let { binary ->
                    val destination = bundle.getDataFile("gitlab-lsp")
                    if (destination.createNewFile()) {
                        destination.writeText(binary.readText())
                    } else {
                        Platform.getLog(FrameworkUtil.getBundle(javaClass)).warn("Unable to create language server binary in data path")
                    }
                    destination.setExecutable(true)
                    Platform.getLog(FrameworkUtil.getBundle(javaClass)).info("Successfully installed language server from package")
                }
//            commands = listOf(destination.canonicalPath)
            }
        } catch (e: Throwable) {
            Platform.getLog(FrameworkUtil.getBundle(javaClass)).error(e.message, e)
        }
    }
}