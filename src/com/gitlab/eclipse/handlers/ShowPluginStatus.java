package com.gitlab.eclipse.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.jface.dialogs.MessageDialog;

public class ShowPluginStatus extends AbstractHandler {
	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		return null;
	}
	
	@Override
	public boolean isEnabled() {
		return true;
	}
	
	@Override
	public boolean isHandled() {
		// TODO Auto-generated method stub
		return true;
	}
}
