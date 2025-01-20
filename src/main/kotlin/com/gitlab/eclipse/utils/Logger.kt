package com.gitlab.eclipse.utils

import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.osgi.framework.FrameworkUtil

inline fun <reified T> logger(): ILog = Platform.getLog(FrameworkUtil.getBundle(T::class.java))
