package com.gitlab.eclipse.lsp

import org.eclipse.core.runtime.FileLocator
import org.eclipse.core.runtime.Path
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle
import org.osgi.framework.FrameworkUtil
import java.net.URL

class LanguageServerInstaller {
    private val logger by lazy { Platform.getLog(FrameworkUtil.getBundle(javaClass)) }

    fun install(): String? {
        try {
            // TODO: Allow to load platform dependent binaries.
            val bundle: Bundle? = Platform.getBundle("com.gitlab.eclipse.gitlab-language-server.cocoa.macosx.aarch64")
            if (bundle != null) {
                val bin: URL? = FileLocator.find(bundle, Path.fromOSString("/bin/gitlab-lsp"))

                return bin?.let { binary ->
                    val destination = bundle.getDataFile("gitlab-lsp")
                    if (destination.createNewFile()) {
                        destination.writeText(binary.readText())
                    } else {
                        logger.warn("Unable to create language server binary in data path")
                    }
                    destination.setExecutable(true)
                    logger.info("Successfully installed language server from package")
                    return@let destination.absolutePath
                }
            }
        } catch (e: Throwable) {
            logger.error(e.message, e)
        }

        return null
    }
}
