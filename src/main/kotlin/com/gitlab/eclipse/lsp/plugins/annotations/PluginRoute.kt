package com.gitlab.eclipse.lsp.plugins.annotations

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class PluginRoute(val pluginId: String)
