/*******************************************************************************
 * Copyright (c) 2013, 2014 Red Hat, Inc. 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * 	Contributors:
 * 		 Red Hat Inc. - initial API and implementation and/or initial documentation
 *******************************************************************************/
package org.eclipse.thym.android.ui.internal.statushandler;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferenceDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.thym.ui.status.AbstractStatusHandler;
import org.eclipse.ui.dialogs.PreferencesUtil;

/**
 * Status handler for missing JDK.
 * 
 * @author Wojciech Galanciak, 2014
 *
 */
public class MissingJDKStatusHandler extends AbstractStatusHandler {

	private static final int DEFINE_JRE_INDEX;
	private static final String[] BUTTON_LABELS;

	static {
		if (Platform.OS_WIN32.equals(Platform.getOS())) {
			DEFINE_JRE_INDEX = 0;
			BUTTON_LABELS = new String[] { "Define JRE", "Cancel" };
		} else {
			DEFINE_JRE_INDEX = 1;
			BUTTON_LABELS = new String[] { "Cancel", "Define JRE" };
		}
	}

	private class MissingJDKDialog extends MessageDialog {

		public MissingJDKDialog(Shell parentShell) {
			super(
					parentShell,
					"Missing JDK",
					null,
					"JDK is not defined in the system. In order to build Android application either set JAVA_HOME environment variable or define JRE in preferences which points to JDK home folder.",
					MessageDialog.INFORMATION, BUTTON_LABELS, DEFINE_JRE_INDEX);
		}

	}

	@Override
	public void handle(IStatus status) {
		MessageDialog dialog = new MissingJDKDialog(getShell());
		if (dialog.open() == DEFINE_JRE_INDEX) {
			PreferenceDialog prefsDialog = PreferencesUtil
					.createPreferenceDialogOn(
							getShell(),
							"org.eclipse.jdt.debug.ui.preferences.VMPreferencePage",
							null, null);
			prefsDialog.open();
		}
	}

	@Override
	public void handle(CoreException e) {
		handle(e.getStatus());
	}
	
}
