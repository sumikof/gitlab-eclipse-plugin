package com.gitlab.eclipse.lsp.plugins

/**
 * Classes inheriting this class that are registered in a Koin module will automatically be registered
 * to handle messages from the Language Server.
 * Controllers should be public and should only have methods which
 * are marked with @PluginRequest or @PluginNotification.
 *
 *
 * Example:
 * ```
 * fun myModule() = module {
 *  single<GitLabDuoChatWebViewController> { GitLabDuoChatWebViewController() } bind PluginController::class
 * }
 *
 * class DuoChatPluginController : PluginController("duo-chat") {
 *   @PluginRequest("getCurrentFileContext") fun getCurrentFileContext() {}
 *   @PluginNotification("insertCodeSnippet") fun insertCodeSnippet(payload: Payload) {}
 * }
 * ```
 */
open class PluginController(val pluginId: String)
