package com.gitlab.eclipse.utils

import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import kotlin.reflect.full.companionObject

// Inspired from https://github.com/apache/logging-log4j-kotlin
interface Logger {
  val logger: ILog
    get() = createKotlinLogger(javaClass)
}

private fun <T : Any> createKotlinLogger(ofClass: Class<T>): ILog {
  val unwrapped = unwrapCompanionClass(ofClass)
  return Platform.getLog(unwrapped)
}

private fun <T : Any> unwrapCompanionClass(ofClass: Class<T>): Class<*> {
  return if (ofClass.enclosingClass?.kotlin?.companionObject?.java == ofClass) {
    ofClass.enclosingClass
  } else {
    ofClass
  }
}
