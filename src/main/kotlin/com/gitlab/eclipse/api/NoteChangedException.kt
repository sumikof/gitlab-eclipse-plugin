package com.gitlab.eclipse.api

/**
 * The note's body on the server differs from the body that was displayed when editing started
 * (design §13). Nothing was sent.
 */
class NoteChangedException : RuntimeException("The comment changed in GitLab after it was loaded")
