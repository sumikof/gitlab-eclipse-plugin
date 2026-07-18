package com.gitlab.eclipse.suggestions.ui;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.PlatformUI;

import com.gitlab.eclipse.suggestions.StreamingCompletionEvents;

/** Registers the editor tracker once the workbench UI is up. */
public class SuggestionStartup implements IStartup {

	@Override
	public void earlyStartup() {
		Display.getDefault().asyncExec(() -> {
			StreamingCompletionEvents.subscribe(response -> {
				CompletionSessionManager manager = SuggestionSessions.active();
				if (manager != null) {
					manager.onStreamingNotification(response);
				}
			});
			IWorkbench workbench = PlatformUI.getWorkbench();
			EditorTracker tracker = new EditorTracker();
			for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
				window.getPartService().addPartListener(tracker);
			}
			workbench.addWindowListener(new IWindowListener() {
				@Override
				public void windowOpened(IWorkbenchWindow window) {
					window.getPartService().addPartListener(tracker);
				}

				@Override
				public void windowClosed(IWorkbenchWindow window) {
				}

				@Override
				public void windowActivated(IWorkbenchWindow window) {
				}

				@Override
				public void windowDeactivated(IWorkbenchWindow window) {
				}
			});
			IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
			if (window != null && window.getActivePage() != null
					&& window.getActivePage().getActiveEditor() != null) {
				tracker.activatePart(window.getActivePage().getActiveEditor());
			}
		});
	}
}
