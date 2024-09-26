package com.gitlab.eclipse.views;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.*;
import org.eclipse.ui.part.ViewPart;

import com.gitlab.eclipse.lsp.GitLabLanguageServerProvider;
import com.gitlab.eclipse.lsp.WebviewInfo;
import com.gitlab.eclipse.storage.SecretStorage;

public class LanguageServerBrowserView extends ViewPart
{

	/**
	 * The ID of the view as specified by the extension.
	 */
	public static final String ID = "com.gitlab.eclipse.views.LanguageServerBrowserView";

	@Inject
	Shell shell;

	private Action refactorCode = setPersonalAccessTokenAction();

	private Browser browser;

	@Override
	public void createPartControl(Composite parent) {
		browser = new Browser(parent, SWT.WEBKIT);
		browser.setText(webviewContent(GitLabLanguageServerProvider.languageServer != null, "duo-chat"));

		var refreshLanguageServerWebviews = new BrowserFunction(browser, "refreshLanguageServerWebviews") {
			@Override
			public Object function(Object[] arguments) {
				return browser.setText(webviewContent(GitLabLanguageServerProvider.languageServer != null, "duo-chat"));
			}
		};

		browser.addDisposeListener((event) -> {
			refreshLanguageServerWebviews.dispose();
		});

		var viewSite = getViewSite();
		var bars = viewSite.getActionBars();
		
		var dropdown = bars.getMenuManager();
		dropdown.add(refactorCode);
	}

	@Override
	public void setFocus() {
		browser.setFocus();
	}

	@Override
	public void dispose() {
		super.dispose();
	}

	private String webviewContent(boolean langaugeServerEnabled, String webviewId) {
		String js = null;
		try (InputStream inputStream = getClass().getResourceAsStream("LanguageServerBrowserView.js")) {
			js = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
		}
		StringBuilder buffer = new StringBuilder();

		buffer.append("<!doctype html>");
		buffer.append("<html lang=\"en\">");
		buffer.append("<head>");
		buffer.append("<meta charset=\"utf-8\">");
		buffer.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
		buffer.append("<title>GitLab</title>");
		buffer.append("<script>" + js + "</script>");
		buffer.append("</head>");

		var webviews = new ArrayList<WebviewInfo>();
		if (langaugeServerEnabled) {
			// TODO: Trigger a browser event instead of synchronously handling this event?
			webviews.addAll(GitLabLanguageServerProvider.languageServer.webviewMetadata()
						.completeOnTimeout(new ArrayList<>(), 10L, TimeUnit.SECONDS)
						.join());

			var redirect = webviews.stream()
					.filter((w) -> webviewId.equals(w.getId()))
					.findFirst()
					.map((w) -> w.getUris())
					.map((l) -> l.getFirst())
					.orElse(null);
			if (redirect != null) {
				buffer.append("<meta http-equiv=\"Refresh\" content=\"0; url='" + redirect + "'\" />");
			}
		}

		buffer.append("<body>");
		buffer.append("<p>If the automatic redirect fails open the webview below:</p>");

		if (webviews.isEmpty()) {
			buffer.append("<button onclick=\"refreshLanguageServerWebviews()\">Refresh here</button>");
		} else {
			buffer.append("<ul>");
			for (var webview : webviews) {
				// TODO: Remove this exception once we remove the duplicate view.
				if ("GitLab: Duo Chat".equals(webview.getTitle())) {
					continue;
				}

				var uri = webview.getUris().getFirst();
				buffer.append("<li><a href=\"" + uri + "\">" + webview.getTitle() + "</a></li>");
			}
			buffer.append("</ul>");
		}

		buffer.append("</body>");
		buffer.append("</html>");
		return buffer.toString();
	}

	private Action setPersonalAccessTokenAction() {
		Action action = new Action() {
			public void run() {
				var inputDialog = new InputDialog(shell, null, "Set GitLab personal access token: ", null, null);
				inputDialog.open();

				var secretStorage = new SecretStorage("gitlab.com");
				try {
					secretStorage.putSecret("personal_access_token", inputDialog.getValue());
				} catch (StorageException e) {
					e.printStackTrace();
				}
			}
		};
		action.setText("Set GitLab personal access token");
		action.setToolTipText("Set GitLab personal access token");
		action.setImageDescriptor(
				PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(ISharedImages.IMG_ETOOL_HOME_NAV));

		return action;
	}
}
