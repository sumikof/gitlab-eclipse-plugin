package com.gitlab.eclipse.utils

import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform

inline fun <reified T> logger(): ILog = Platform.getLog(T::class.java)
