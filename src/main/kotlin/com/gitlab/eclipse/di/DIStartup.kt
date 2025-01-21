package com.gitlab.eclipse.di

import org.eclipse.ui.IStartup

class DIStartup : IStartup {
  override fun earlyStartup() {
    Workspace.load("com.gitlab.eclipse")
  }
}
