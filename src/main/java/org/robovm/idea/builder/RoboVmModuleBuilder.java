/*
 * Copyright (C) 2015 RoboVM AB
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/gpl-2.0.html>.
 */
package org.robovm.idea.builder;

import com.intellij.ide.util.projectWizard.JavaModuleBuilder;
import com.intellij.ide.util.projectWizard.ModuleWizardStep;
import com.intellij.ide.util.projectWizard.WizardContext;
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.model.ExternalSystemDataKeys;
import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemSettings;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.module.StdModuleTypes;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.projectRoots.ProjectJdkTable;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.LanguageLevelModuleExtension;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.roots.ui.configuration.ModulesProvider;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.ui.AppUIUtil;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.project.MavenProjectsManager;
import org.jetbrains.plugins.gradle.GradleManager;
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolver;
import org.jetbrains.plugins.gradle.service.project.wizard.GradleModuleBuilder;
import org.jetbrains.plugins.gradle.settings.DistributionType;
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings;
import org.jetbrains.plugins.gradle.util.GradleConstants;
import org.jetbrains.plugins.gradle.util.GradleUtil;
import org.robovm.compiler.Version;
import org.robovm.idea.RoboVmPlugin;
import org.robovm.idea.sdk.RoboVmSdkType;
import org.robovm.templater.Templater;

import java.io.File;
import java.io.IOException;

/**
 * Creates all the files for a new project/module using the templater.
 * See https://android.googlesource.com/platform/tools/adt/idea/+/7ac63164a27e301d45d8640852a0bdaa6eabbad0/android/src/org/jetbrains/android/newProject/AndroidModuleBuilder.java
 */
public class RoboVmModuleBuilder extends JavaModuleBuilder {
    public static final String ROBOVM_VERSION_PLACEHOLDER = "__robovmVersion__";
    public static final String PACKAGE_NAME_PLACEHOLDER = "__packageName__";
    public static final String APP_NAME_PLACEHOLDER = "__appName__";

    private String templateName;
    private String packageName;
    private String mainClassName;
    private String appName;
    private String appId;
    private BuildSystem buildSystem;

    public RoboVmModuleBuilder(String templateName) {
        this.templateName = templateName;
    }

    public void setupRootModel(final ModifiableRootModel rootModel) throws ConfigurationException {
        // set the RoboVM SDK instead of a JDK, but only if
        // there's no build system involved
        if(buildSystem == BuildSystem.None) {
            for (Sdk sdk : ProjectJdkTable.getInstance().getAllJdks()) {
                if (sdk.getHomePath().equals(RoboVmPlugin.getSdkHome().getAbsolutePath())) {
                    myJdk = sdk;
                    break;
                }
            }
        } else {
            myJdk = RoboVmSdkType.findBestJdk();
        }
        rootModel.getModuleExtension(LanguageLevelModuleExtension.class).setLanguageLevel(LanguageLevel.HIGHEST);
        super.setupRootModel(rootModel);

        // extract the template files and setup the source
        // folders
        VirtualFile[] files = rootModel.getContentRoots();
        if (files.length > 0) {
            final VirtualFile contentRoot = files[0];
            final Project project = rootModel.getProject();
            Templater templater = new Templater(templateName);
            templater.appId(appId);
            templater.appName(appName);
            templater.executable(appName);
            templater.mainClass(packageName + "." + mainClassName);
            templater.packageName(packageName);
            templater.buildProject(new File(contentRoot.getCanonicalPath()));
            applyBuildSystem(project, rootModel, contentRoot);
            contentRoot.refresh(false, true);

            for(ContentEntry entry: rootModel.getContentEntries()) {
                for(SourceFolder srcFolder: entry.getSourceFolders()) {
                    entry.removeSourceFolder(srcFolder);
                }
                entry.addSourceFolder(contentRoot.findFileByRelativePath("src/main/java"), false);
            }
        }
    }

    private void applyBuildSystem(final Project project, final ModifiableRootModel model, VirtualFile contentRoot) {
        if(buildSystem == BuildSystem.Gradle) {
            try {
                String template = IOUtils.toString(RoboVmModuleBuilder.class.getResource("/build.gradle"), "UTF-8");
                template = template.replaceAll(ROBOVM_VERSION_PLACEHOLDER, Version.getVersion());
                File buildFile = new File(contentRoot.getCanonicalPath() + "/build.gradle");
                FileUtils.write(buildFile, template);

                GradleProjectSettings gradleSettings = new GradleProjectSettings();
                gradleSettings.setDistributionType(DistributionType.DEFAULT_WRAPPED);
                gradleSettings.setExternalProjectPath(getContentEntryPath());
                AbstractExternalSystemSettings settings = ExternalSystemApiUtil.getSettings(model.getProject(), GradleConstants.SYSTEM_ID);
                project.putUserData(ExternalSystemDataKeys.NEWLY_CREATED_PROJECT, Boolean.TRUE);
                settings.linkProject(gradleSettings);

                FileDocumentManager.getInstance().saveAllDocuments();
                ImportSpecBuilder builder = new ImportSpecBuilder(model.getProject(), GradleConstants.SYSTEM_ID);
                builder.forceWhenUptodate(true);
                ExternalSystemUtil.refreshProjects(builder);
            } catch (IOException e) {
                // nothing to do here, can't log or throw an exception
            }
        } else if(buildSystem == BuildSystem.Maven) {
            try {
                String template = IOUtils.toString(RoboVmModuleBuilder.class.getResource("/pom.xml"), "UTF-8");
                template = template.replaceAll(ROBOVM_VERSION_PLACEHOLDER, Version.getVersion());
                template = template.replaceAll(PACKAGE_NAME_PLACEHOLDER, packageName);
                template = template.replaceAll(APP_NAME_PLACEHOLDER, mainClassName);
                File buildFile = new File(contentRoot.getCanonicalPath() + "/pom.xml");
                FileUtils.write(buildFile, template);
                AppUIUtil.invokeLaterIfProjectAlive(project, new Runnable() {
                    @Override
                    public void run() {
                        MavenProjectsManager.getInstance(project).forceUpdateAllProjectsOrFindAllAvailablePomFiles();
                        FileDocumentManager.getInstance().saveAllDocuments();
                        ImportSpecBuilder builder = new ImportSpecBuilder(model.getProject(), new ProjectSystemId("MAVEN"));
                        builder.forceWhenUptodate(true);
                        ExternalSystemUtil.refreshProjects(builder);
                    }
                });
            } catch (IOException e) {
                // nothing to do here, can't log or throw an exception
            }
        }
    }

    public void setApplicationId(String applicationId) {
        this.appId = applicationId;
    }

    public void setApplicationName(String applicationName) {
        this.appName = applicationName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public void setMainClassName(String mainClassName) {
        this.mainClassName = mainClassName;
    }

    public void setBuildSystem(BuildSystem buildSystem) {
        this.buildSystem = buildSystem;
    }

    public ModuleType getModuleType() {
        return StdModuleTypes.JAVA;
    }

    @Nullable
    @Override
    public String getBuilderId() {
        return this.getClass().getName() + templateName;
    }

    @Override
    public ModuleWizardStep[] createWizardSteps(WizardContext wizardContext, ModulesProvider modulesProvider) {
        return new ModuleWizardStep[] { new RoboVmModuleWizardStep(this, wizardContext, modulesProvider)};
    }

    public static enum BuildSystem {
        Gradle,
        Maven,
        None
    }
}
