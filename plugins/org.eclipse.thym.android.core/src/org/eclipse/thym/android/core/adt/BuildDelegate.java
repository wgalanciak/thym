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
package org.eclipse.thym.android.core.adt;

import java.io.File;
import java.io.FilenameFilter;

import org.eclipse.ant.launching.IAntLaunchConstants;
import org.eclipse.core.externaltools.internal.IExternalToolConstants;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.internal.launching.StandardVMType;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.IVMInstallType;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.thym.android.core.AndroidConstants;
import org.eclipse.thym.android.core.AndroidCore;
import org.eclipse.thym.core.HybridProject;
import org.eclipse.thym.core.platform.AbstractNativeBinaryBuildDelegate;

public class BuildDelegate extends AbstractNativeBinaryBuildDelegate {

	private File binaryDirectory;

	public BuildDelegate() {
	}

	@Override
	public void buildNow(IProgressMonitor monitor) throws CoreException {
		if (monitor.isCanceled())
			return;

		// TODO: use extension point to create
		// the generator.
		AndroidProjectGenerator creator = new AndroidProjectGenerator(
				this.getProject(), getDestination(), "android");

		SubProgressMonitor generateMonitor = new SubProgressMonitor(monitor, 1);
		File projectDirectory = creator.generateNow(generateMonitor);
		monitor.worked(1);
		if (monitor.isCanceled()) {
			return;
		}
		buildProject(projectDirectory, monitor);
		monitor.done();
	}

	public void buildProject(File projectLocation, IProgressMonitor monitor)
			throws CoreException {
		doBuildProject(projectLocation, false, monitor);
	}

	public void buildLibraryProject(File projectLocation,
			IProgressMonitor monitor) throws CoreException {
		doBuildProject(projectLocation, true, monitor);
	}

	/**
	 * Returns the directory where build artifacts are stored. Will return null
	 * if the build is not yet complete or {@link #buildNow(IProgressMonitor)}
	 * is not called yet for this instance.
	 * 
	 * @return
	 */
	public File getBinaryDirectory() {
		return binaryDirectory;
	}

	private void doBuildProject(File projectLocation, boolean isLibrary,
			IProgressMonitor monitor) throws CoreException {
		ILaunchManager launchManager = DebugPlugin.getDefault()
				.getLaunchManager();
		ILaunchConfigurationType antLaunchConfigType = launchManager
				.getLaunchConfigurationType(IAntLaunchConstants.ID_ANT_LAUNCH_CONFIGURATION_TYPE);
		if (antLaunchConfigType == null) {
			throw new CoreException(new Status(IStatus.ERROR,
					AndroidCore.PLUGIN_ID,
					"Ant launch configuration type is not available"));
		}
		ILaunchConfigurationWorkingCopy wc = antLaunchConfigType.newInstance(
				null, "Android project builder"); //$NON-NLS-1$
		wc.setContainer(null);
		File buildFile = new File(projectLocation,
				AndroidConstants.FILE_XML_BUILD);
		if (!buildFile.exists()) {
			throw new CoreException(new Status(IStatus.ERROR,
					AndroidCore.PLUGIN_ID, "build.xml does not exist in "
							+ projectLocation.getPath()));
		}
		wc.setAttribute(IExternalToolConstants.ATTR_LOCATION,
				buildFile.getPath());
		String target = null;
		if (isLibrary) {
			target = "jar";
		} else {
			target = "debug";
			if (isRelease()) {
				target = "release";
			}
		}
		wc.setAttribute(IAntLaunchConstants.ATTR_ANT_TARGETS, target);
		wc.setAttribute(IAntLaunchConstants.ATTR_DEFAULT_VM_INSTALL, true);

		wc.setAttribute(IExternalToolConstants.ATTR_LAUNCH_IN_BACKGROUND, false);
		wc.setAttribute(DebugPlugin.ATTR_CAPTURE_OUTPUT, true);

		setupVM(wc);

		ILaunchConfiguration launchConfig = wc.doSave();
		if (monitor.isCanceled()) {
			return;
		}

		launchConfig.launch(ILaunchManager.RUN_MODE, monitor, true, true);

		binaryDirectory = new File(projectLocation, AndroidConstants.DIR_BIN);
		if (isLibrary) {
			// no checks for libs
		} else {
			HybridProject hybridProject = HybridProject
					.getHybridProject(getProject());
			if (isRelease()) {
				setBuildArtifact(new File(binaryDirectory,
						hybridProject.getBuildArtifactAppName()
								+ "-release-unsigned.apk"));
			} else {
				setBuildArtifact(new File(binaryDirectory,
						hybridProject.getBuildArtifactAppName() + "-debug.apk"));
			}
			if (!getBuildArtifact().exists()) {
				throw new CoreException(new Status(IStatus.ERROR,
						AndroidCore.PLUGIN_ID,
						"Build failed... Build artifact does not exist"));
			}
		}
	}

	private void setupVM(ILaunchConfigurationWorkingCopy wc)
			throws CoreException {
		// If JAVA_HOME is set then do nothing.
		if (System.getenv("JAVA_HOME") != null) {
			return;
		}
		// If JAVA_HOME is not set then firstly check if a default JRE points to
		// JDK. If it does then do nothing again.
		IVMInstall defaultVM = JavaRuntime.getDefaultVMInstall();
		// If not then try to find non-default one or create a new one and set
		// it for this configuration.
		IVMInstall customVM = null;
		if (hasJavac(defaultVM)) {
			customVM = defaultVM;
		} else {
			IVMInstallType[] installs = JavaRuntime.getVMInstallTypes();
			for (IVMInstallType vmInstallType : installs) {
				if (vmInstallType.getId().equals(
						StandardVMType.ID_STANDARD_VM_TYPE)) {
					// Try to find a non-default existing JRE.
					IVMInstall[] vmInstalls = vmInstallType.getVMInstalls();
					if (vmInstalls != null) {
						for (IVMInstall vmInstall : vmInstalls) {
							if (hasJavac(vmInstall)) {
								customVM = vmInstall;
								break;
							}
						}
					}
					// If it does not exist then try to create a new one.
					if (customVM == null) {
						File jdkHome = findJdk();
						if (jdkHome != null) {
							IVMInstall vmInstall = vmInstallType
									.createVMInstall(createUniqueId(vmInstallType));
							vmInstall.setName(jdkHome.getName());
							vmInstall.setInstallLocation(jdkHome);
							JavaRuntime.saveVMConfiguration();
							customVM = vmInstall;
						}
					}
					break;
				}
			}
			if (customVM != null) {
				wc.setAttribute(
						JavaRuntime.JRE_CONTAINER,
						new Path(JavaRuntime.JRE_CONTAINER)
								.append(StandardVMType.ID_STANDARD_VM_TYPE)
								.append(customVM.getName()).toString());
				wc.setAttribute(IAntLaunchConstants.ATTR_DEFAULT_VM_INSTALL,
						false);
			}
		}
		if (customVM != null) {
			wc.setAttribute(
					IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME,
					IAntLaunchConstants.ID_ANT_PROCESS_TYPE);
			wc.setAttribute(DebugPlugin.ATTR_PROCESS_FACTORY_ID,
					"org.eclipse.ant.ui.remoteAntProcessFactory");
		}
	}

	private File findJdk() {
		if (Platform.getOS().equals(Platform.OS_WIN32)) {
			// try to find jdk on PATH
			String pathVariable = System.getenv("Path");
			if (pathVariable != null) {
				String[] segments = pathVariable.split(File.pathSeparator);
				for (String path : segments) {
					File file = new File(path);
					if (hasJavac(file)) {
						return file.getParentFile();
					}
				}
			}
			String programFiles = System.getenv("ProgramFiles");
			File javaFolder = new File(programFiles, "Java");
			if (javaFolder.exists()) {
				File[] jdkFolders = getJDKs(javaFolder);
				if (jdkFolders != null && jdkFolders.length > 0) {
					return jdkFolders[jdkFolders.length - 1];
				}
			}
		}
		if (Platform.getOS().equals(Platform.OS_MACOSX)) {
			return findJdkMacOSX();
		}
		return null;
	}

	private File findJdkMacOSX() {
		File vmsFolder = new File("/Library/Java/JavaVirtualMachines");
		if (vmsFolder.exists()) {
			File[] jdkFolders = getJDKs(vmsFolder);
			if (jdkFolders != null && jdkFolders.length > 0) {
				File jdkRoot = jdkFolders[jdkFolders.length - 1];
				return new File(jdkRoot, "Contents/Home");
			}
		}
		return null;
	}
	
	private File[] getJDKs(File root) {
		return root.listFiles(new FilenameFilter() {
			
			@Override
			public boolean accept(File file, String name) {
				return name.startsWith("jdk");
			}
		});
	}

	private boolean hasJavac(IVMInstall vmInstall) {
		if (vmInstall != null) {
			File binFolder = new File(vmInstall.getInstallLocation(), "bin");
			if (binFolder.exists()) {
				return hasJavac(binFolder);
			}
		}
		return false;
	}

	private boolean hasJavac(File binFolder) {
		File javac = new File(binFolder, "javac");
		if (!javac.exists()) {
			javac = new File(binFolder, "javac.exe");
			return javac.exists();
		}
		return true;
	}

	/**
	 * Creates a unique name for the VMInstallType
	 * 
	 * @param vmType
	 *            the vm install type
	 * @return a unique name
	 */
	private static String createUniqueId(IVMInstallType vmType) {
		String id = null;
		do {
			id = String.valueOf(System.currentTimeMillis());
		} while (vmType.findVMInstall(id) != null);
		return id;
	}

}
