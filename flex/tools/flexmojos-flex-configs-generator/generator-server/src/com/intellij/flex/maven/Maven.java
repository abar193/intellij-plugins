package com.intellij.flex.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.LifecycleExecutionPlanCalculator;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginManagerException;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantLock;

final class Maven {
  private final HashMap<File, ProjectCacheData> projectsCache = new HashMap<File, ProjectCacheData>();
  private final HashMap<String, ArrayList<MojoExecution>> mojoExecutionPoolMap = new HashMap<String, ArrayList<MojoExecution>>();
  private final PlexusContainer plexusContainer;
  private final MavenSession session;

  private final BuildPluginManager pluginManager;
  private final ReentrantLock projectCacheLock = new ReentrantLock();

  public Maven(PlexusContainer plexusContainer, MavenSession session) throws ComponentLookupException {
    this.plexusContainer = plexusContainer;
    this.session = session;
    pluginManager = plexusContainer.lookup(BuildPluginManager.class);
  }

  public MavenProject readProject(final File pomFile) throws ComponentLookupException, ProjectBuildingException {
    projectCacheLock.lock();
    ProjectCacheData projectCacheData;
    boolean unlocked = false;
    try {
      projectCacheData = projectsCache.get(pomFile);
      if (projectCacheData != null) {
        projectCacheLock.unlock();
        unlocked = true;
        
        while (projectCacheData.project == null) {
          try {
            Thread.sleep(5);
          }
          catch (InterruptedException e) {
            break;
          }
        }
        return projectCacheData.project;
      }

      projectCacheData = new ProjectCacheData();
      projectsCache.put(pomFile, projectCacheData);
    }
    finally {
      if (!unlocked) {
        projectCacheLock.unlock();
      }
    }

    final ProjectBuildingRequest projectBuildingRequest = session.getRequest().getProjectBuildingRequest();
    projectBuildingRequest.setResolveDependencies(true);

    projectBuildingRequest.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
    projectBuildingRequest.setRepositorySession(session.getRepositorySession());
    projectCacheData.project = plexusContainer.lookup(ProjectBuilder.class).build(pomFile, projectBuildingRequest).getProject();
    return projectCacheData.project;
  }

  private static final class ProjectCacheData {
    private MavenProject project;
  }

  private synchronized MojoExecution getCachedMojoExecution(Plugin plugin, String goal) {
    final String poolMapKey = createMojoExecutionPoolCacheKey(goal, plugin);
    final ArrayList<MojoExecution> mojoExecutions = mojoExecutionPoolMap.get(poolMapKey);
    if (mojoExecutions == null) {
      mojoExecutionPoolMap.put(poolMapKey, new ArrayList<MojoExecution>());
    }
    else if (!mojoExecutions.isEmpty()) {
      return mojoExecutions.remove(mojoExecutions.size());
    }

    return null;
  }

  public MojoExecution createMojoExecution(Plugin plugin, String goal, MavenProject project) throws Exception {
    MojoExecution mojoExecution = getCachedMojoExecution(plugin, goal);
    if (mojoExecution != null) {
      return mojoExecution;
    }

    MojoDescriptor mojoDescriptor = pluginManager.getMojoDescriptor(plugin, goal, project.getRemotePluginRepositories(), session.getRepositorySession());
    mojoExecution = new MojoExecution(mojoDescriptor, "default-cli", MojoExecution.Source.CLI);
    plexusContainer.lookup(LifecycleExecutionPlanCalculator.class).setupMojoExecution(session, project, mojoExecution);
    return mojoExecution;
  }

  public synchronized void releaseMojoExecution(String goal, MojoExecution mojoExecution) throws Exception {
    Plugin plugin = mojoExecution.getPlugin();
    mojoExecution.setConfiguration(null);
    mojoExecutionPoolMap.get(createMojoExecutionPoolCacheKey(goal, plugin)).add(mojoExecution);
  }

  private static String createMojoExecutionPoolCacheKey(String goal, Plugin plugin) {
    // only for flexmojos plugin group
    return plugin.getArtifactId() + "-" + plugin.getVersion() + "-" + goal;
  }

  public ClassRealm getPluginRealm(MojoExecution mojoExecution) throws PluginManagerException, PluginResolutionException {
    return pluginManager.getPluginRealm(session, mojoExecution.getMojoDescriptor().getPluginDescriptor());
  }
}
