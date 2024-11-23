package com.gitlab.eclipse.preferences

import com.gitlab.eclipse.storage.SecretStorage
import org.eclipse.core.runtime.preferences.InstanceScope
import org.eclipse.jface.preference.BooleanFieldEditor
import org.eclipse.jface.preference.FieldEditorPreferencePage
import org.eclipse.jface.preference.StringFieldEditor
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchPreferencePage
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.osgi.framework.FrameworkUtil

class GitLabPreferencePage : FieldEditorPreferencePage(GRID), IWorkbenchPreferencePage {
    init {
        description = "GitLab Duo plugin preferences"
    }

    public override fun createFieldEditors() {
        // Connection
        addField(StringFieldEditor(PreferenceConstants.GITLAB_INSTANCE_URL, "Connection URL", fieldEditorParent))
        addField(
            BooleanFieldEditor(
                PreferenceConstants.IGNORE_CERTIFICATE_ERRORS, "Ignore Certificate Errors",
                fieldEditorParent
            )
        )

        // Authentication
        //TODO: Ensure first-time load succeeds given empty value does not break the entire page.
        addField(
            SecretStringFieldEditor(
                SecretStorage("gitlab.com"), "personal_access_token", "Personal Access Token",
                fieldEditorParent
            )
        )

        // Language Server
        addField(
            StringFieldEditor(
                PreferenceConstants.LANGUAGE_SERVER_LOG_LEVEL, "Language Server Log Level",
                fieldEditorParent
            )
        )
        addField(
            BooleanFieldEditor(
                PreferenceConstants.LANGUAGE_SERVER_STREAM_CODE_GENERATIONS, "Stream Code Generations",
                fieldEditorParent
            )
        )
    }

    override fun init(workbench: IWorkbench) {
        val bundleId = FrameworkUtil.getBundle(javaClass).bundleId.toString()
        val store = ScopedPreferenceStore(InstanceScope.INSTANCE, bundleId)
        preferenceStore = store

        PreferenceInitializer(store).initializeDefaultPreferences()
    }
}