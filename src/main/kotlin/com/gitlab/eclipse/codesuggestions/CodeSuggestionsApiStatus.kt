package com.gitlab.eclipse.codesuggestions

/**
 * Global flag indicating whether the Code Suggestions API is currently available.
 * This flag is updated by GitLabLanguageServerClient when API status notifications are received.
 * Used by CodeSuggestionsSession and CodeSuggestionsStatusHandler to control behavior.
 */
@Volatile
var isCodeSuggestionsApiAvailable: Boolean = false
