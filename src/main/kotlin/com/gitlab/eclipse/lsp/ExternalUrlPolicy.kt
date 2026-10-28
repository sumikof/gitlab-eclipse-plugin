package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.navigation.BrowserUrlVerdict
import com.gitlab.eclipse.navigation.browserUrlVerdict

/**
 * True when [url] may be opened in the user's external browser.
 *
 * The URI arrives from the language server, so it is untrusted: only absolute http/https URLs
 * with a host and no userinfo are allowed through. Userinfo (`user@host`) is rejected because it
 * lets an attacker disguise the real target host as credentials in front of a host they control,
 * e.g. `https://gitlab.com@evil.example/x` actually points at `evil.example`.
 *
 * The decision itself is the BrowserLauncher gate's ([browserUrlVerdict], D8a); this only asks it.
 */
fun isBrowsableExternalUrl(url: String?): Boolean = browserUrlVerdict(url) is BrowserUrlVerdict.Allowed
