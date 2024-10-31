package com.gitlab.eclipse.views;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.time.Instant;
import java.util.Date;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

public class LanguageServerBrowserView extends ViewPart
{
	private Browser browser;

	/**
	 * The ID of the view as specified by the extension.
	 */
	public static final String ID = "com.gitlab.eclipse.views.LanguageServerBrowserView";

	@Override
	public void createPartControl(Composite parent) {
		browser = new Browser(parent, SWT.WEBKIT);
		browser.setText(webviewContent());
	}

	@Override
	public void setFocus() {
		browser.setFocus();
	}

	@Override
	public void dispose() {
		super.dispose();
	}

	private String webviewContent() {
		String js = null;
		try (InputStream inputStream = getClass().getResourceAsStream("/javascript/LanguageServerBrowserView.js")) {
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
		buffer.append("<body>");
		buffer.append("<p>Hello world from the GitLab for Eclipse.</p>");
		buffer.append("<p>Webview content loaded at: " + DateFormat.getInstance().format(Date.from(Instant.now())) + "</p>");
		buffer.append("</body>");
		buffer.append("</html>");
		return buffer.toString();
	}
}
