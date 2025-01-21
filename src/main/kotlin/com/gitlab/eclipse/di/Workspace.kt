package com.gitlab.eclipse.di

object Workspace {
  val container = DIContainer()
  private val modules: MutableMap<String, DIModule> = mutableMapOf()

  fun load(pkgName: String) {
    if (modules.containsKey(pkgName)) {
      error("Module $pkgName already exists.")
    }

    modules[pkgName] = DIModule(pkgName, container)
  }
}

inline fun <reified T> Workspace.service() = container.getService(T::class.java)
