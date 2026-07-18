package com.gitlab.eclipse.lsp.install;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

/** Background download of the GitLab Language Server with progress reporting. */
public class LanguageServerInstallJob extends Job {
	private final LanguageServerInstaller installer;

	public LanguageServerInstallJob(LanguageServerInstaller installer) {
		super("Downloading GitLab Language Server " + LanguageServerInstaller.VERSION);
		this.installer = installer;
	}

	@Override
	protected IStatus run(IProgressMonitor monitor) {
		monitor.beginTask("Downloading GitLab Language Server (~300 MB, one time)", IProgressMonitor.UNKNOWN);
		var lastReportedMb = new long[] { -1 };
		try {
			installer.install(bytes -> {
				long mb = bytes / (1024 * 1024);
				if (mb / 10 > lastReportedMb[0]) {
					lastReportedMb[0] = mb / 10;
					monitor.subTask(mb + " MB downloaded");
				}
			});
			return Status.OK_STATUS;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Status.CANCEL_STATUS;
		} catch (Exception e) {
			return Status.error(
					"Failed to download the GitLab Language Server. The download will be retried on the next start.",
					e);
		} finally {
			monitor.done();
		}
	}
}
