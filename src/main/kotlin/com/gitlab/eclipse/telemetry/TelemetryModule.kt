package com.gitlab.eclipse.telemetry

import org.koin.dsl.module

val telemetryModule = module {
  // eager loading example
  single(createdAtStart = true) { TelemetryService(get()) }
}