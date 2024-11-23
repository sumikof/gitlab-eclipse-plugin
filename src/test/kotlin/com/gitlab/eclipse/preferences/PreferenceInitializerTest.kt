package com.gitlab.eclipse.preferences

import io.kotest.core.spec.style.DescribeSpec
import io.mockk.*
import org.eclipse.ui.preferences.ScopedPreferenceStore

class PreferenceInitializerTest : DescribeSpec({
    describe("initializeDefaultPreferences") {
        it("builds webview") {
            val store = mockk<ScopedPreferenceStore>(relaxed = true)
            val subject = PreferenceInitializer(store)

            subject.initializeDefaultPreferences()

            verify { store.setDefault(PreferenceConstants.GITLAB_INSTANCE_URL, "https://gitlab.com") }
            verify { store.setDefault(PreferenceConstants.IGNORE_CERTIFICATE_ERRORS, false) }
            verify { store.setDefault(PreferenceConstants.TELEMETRY_ENABLED, true) }
            verify { store.setDefault(PreferenceConstants.LANGUAGE_SERVER_LOG_LEVEL, "info") }
            verify { store.setDefault(PreferenceConstants.LANGUAGE_SERVER_STREAM_CODE_GENERATIONS, true) }
        }
    }
})