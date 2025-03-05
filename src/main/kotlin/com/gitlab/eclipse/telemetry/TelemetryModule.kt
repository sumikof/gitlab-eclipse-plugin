package com.gitlab.eclipse.telemetry

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import org.koin.dsl.module

val telemetryModule = module {
  single<TelemetryService> {
    TelemetryService(get<GitLabLanguageServerWrapper>())
  }
}
