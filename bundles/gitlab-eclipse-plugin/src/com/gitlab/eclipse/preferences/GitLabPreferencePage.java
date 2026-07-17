package com.gitlab.eclipse.preferences;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.osgi.framework.FrameworkUtil;

import com.gitlab.eclipse.lsp.GitLabLanguageServerProvider;
import com.gitlab.eclipse.storage.SecretStorage;

public class GitLabPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {
	public GitLabPreferencePage() {
		super(GRID);
		setDescription("GitLab Duo plugin preferences");
	}
	
	@Override
	public void createFieldEditors() {
		// Connection
		addField(new StringFieldEditor(PreferenceConstants.GITLAB_INSTANCE_URL, "Connection URL", getFieldEditorParent()));
		addField(new BooleanFieldEditor(PreferenceConstants.IGNORE_CERTIFICATE_ERRORS, "Ignore Certificate Errors", getFieldEditorParent()));

		// Authentication
		addField(new SecretStringFieldEditor(new SecretStorage("gitlab.com"), "personal_access_token", "Personal Access Token", getFieldEditorParent()));

		// Language Server
		addField(new StringFieldEditor(PreferenceConstants.LANGUAGE_SERVER_LOG_LEVEL, "Language Server Log Level", getFieldEditorParent()));
		addField(new BooleanFieldEditor(PreferenceConstants.LANGUAGE_SERVER_STREAM_CODE_GENERATIONS, "Stream Code Generations", getFieldEditorParent()));
		addField(new FileFieldEditor(PreferenceConstants.LANGUAGE_SERVER_BINARY_PATH,
				"Language Server Binary (blank = auto-download)", true, getFieldEditorParent()));

		addField(new BooleanFieldEditor(PreferenceConstants.TANUKI_ONLY_SHOW_CODE_MININGS, "Tanuki? Why not enable code mining?", getFieldEditorParent()));
	}

	@Override
	public void init(IWorkbench workbench) {
		var bundleId = String.valueOf(FrameworkUtil.getBundle(getClass()).getBundleId());
		var store = new ScopedPreferenceStore(InstanceScope.INSTANCE, bundleId);
		setPreferenceStore(store);
	
		// TODO: Determine why this is needed on top of the initializer in plugin.xml.
		new PreferenceInitializer(store).initializeDefaultPreferences();
	}
	
	@Override
	protected void performApply() {
		super.performApply();
		GitLabLanguageServerProvider.onDidChangeConfiguration();
	}
}