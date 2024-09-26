package com.gitlab.eclipse.preferences;

import java.util.Optional;

import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.widgets.Composite;

import com.gitlab.eclipse.storage.SecretStorage;

public class SecretStringFieldEditor extends StringFieldEditor {
	private SecretStorage secretStorage;

	public SecretStringFieldEditor(SecretStorage secretStorage, String secretName, String labelText, Composite parent) {
		super(secretName, labelText, parent);
		this.secretStorage = secretStorage;
	}

	@Override
	protected void doLoad() {
		var textField = getTextControl();
		if (textField != null) {
			var value = Optional.ofNullable(secret())
					.map(s -> s.replaceAll(".", "•"))
					.orElse("");
			textField.setText(value);
			oldValue = value;
		}
	}

	@Override
	protected void doLoadDefault() {
		var textField = getTextControl();
		if (textField != null) {
			textField.setText("");
		}
		valueChanged();
	}

	@Override
	protected void doStore() {
		var textField = getTextControl();
		var text = textField.getText();
		if (text.matches("^•+$")) {
			// Avoid overwriting existing secrets with placeholder values. 
			return;
		}

		try {
			secretStorage.putSecret(getPreferenceName(), text);
		} catch (StorageException e) {
			e.printStackTrace();
		};
		doLoad();
	}
	
	@Override
	protected boolean doCheckState() {
		var text = getTextControl().getText();
		if (text.isEmpty()) {
			setErrorMessage(getLabelText() + " must not be empty");
			return false;
		}

		if (!text.matches("^[a-zA-Z0-9-]+$") && !text.matches("^•+$")) {
			setErrorMessage(getLabelText() + " must match pattern ^[a-zA-Z0-9-]+$");
			return false;
		}
		
		return true;
	}

	private String secret() {
		return secretStorage.getSecret(getPreferenceName());
	}
}
