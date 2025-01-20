package com.gitlab.eclipse.extensions

import io.kotest.core.listeners.AfterSpecListener
import io.kotest.core.listeners.BeforeEachListener
import io.kotest.core.listeners.BeforeSpecListener
import io.kotest.core.spec.Spec
import io.kotest.core.test.TestCase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.Platform
import org.osgi.framework.Bundle

object LoggingKotestExtension : BeforeSpecListener, BeforeEachListener, AfterSpecListener {
  override suspend fun beforeSpec(spec: Spec) {
    mockkStatic(Platform::class)
    super.beforeSpec(spec)
  }

  override suspend fun beforeEach(testCase: TestCase) {
    every { Platform.getLog(any<Bundle>()) } returns mockk<ILog>(relaxUnitFun = true)
    every { Platform.getLog(any<Class<*>>()) } returns mockk<ILog>(relaxUnitFun = true)
    super.beforeEach(testCase)
  }

  override suspend fun afterSpec(spec: Spec) {
    unmockkAll()
    super.afterSpec(spec)
  }
}
