package com.gitlab.eclipse.lsp.plugins.annotations

/**
 * Identifies a class as an LSP plugin controller. Classes annotated with
 * this annotation will be automatically registered to handle messages from the Language Server.
 * Controllers should be public and should only have methods which
 * are marked with @PluginRequest or @PluginNotification.
 *
 *
 * Example:
 * ```
 * @PluginController("duo-chat")
 * class DuoChatPluginController {
 *   @PluginRequest("getCurrentFileContext") fun getCurrentFileContext() {}
 *   @PluginNotification("insertCodeSnippet") fun insertCodeSnippet(payload: Payload) {}
 * }
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class PluginController(val pluginId: String)
