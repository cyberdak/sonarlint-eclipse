package org.sonar.ide.eclipse.tests.common;

import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.mortbay.jetty.testing.ServletTester;
import org.sonar.ide.api.Logs;
import org.sonar.ide.eclipse.SonarPlugin;
import org.sonar.ide.test.SourceServlet;
import org.sonar.ide.test.VersionServlet;
import org.sonar.ide.test.ViolationServlet;
import org.sonar.wsclient.Host;
import org.sonar.wsclient.Sonar;
import org.sonar.wsclient.connectors.HttpClient4Connector;

/**
 * Common test case for sonar-ide/eclipse projects.
 * 
 * @author Jérémie Lagarde
 * 
 */
public abstract class AbstractSonarTest {

  protected static final IProgressMonitor monitor = new NullProgressMonitor();
  protected static IWorkspace             workspace;
  protected static SonarPlugin            plugin;
  private static SonarTestServer          testServer;
  private List<MarkerChecker>             markerCheckerList;

  @BeforeClass
  final static public void prepareWorkspace() throws Exception {
    workspace = ResourcesPlugin.getWorkspace();
    IWorkspaceDescription description = workspace.getDescription();
    description.setAutoBuilding(false);
    workspace.setDescription(description);

    plugin = SonarPlugin.getDefault();
    cleanWorkspace();
  }

  final protected String startTestServer() throws Exception {
    if (testServer == null) {
      synchronized (SonarTestServer.class) {
        if (testServer == null) {
          testServer = new SonarTestServer();
          testServer.start();
        }
      }
    }
    return testServer.getBaseUrl();
  }

  final protected String addLocalTestServer() throws Exception {
    String url = startTestServer();
    SonarPlugin.getServerManager().createServer(url);
    return url;
  }

  @AfterClass
  final static public void end() throws Exception {
    // cleanWorkspace();

    IWorkspaceDescription description = workspace.getDescription();
    description.setAutoBuilding(true);
    workspace.setDescription(description);

    if (testServer != null) {
      testServer.stop();
      testServer = null;
    }

  }

  final static private void cleanWorkspace() throws Exception {
    // Job.getJobManager().suspend();
    // waitForJobs();

    List<Host> hosts = new ArrayList<Host>();
    hosts.addAll(SonarPlugin.getServerManager().getServers());
    for (Host host : hosts) {
      SonarPlugin.getServerManager().removeServer(host.getHost());
    }
    IWorkspaceRoot root = workspace.getRoot();
    for (IProject project : root.getProjects()) {
      project.delete(true, true, monitor);
    }
  }

  /**
   * Import test project into the Eclipse workspace
   * 
   * @return created projects
   */
  protected IProject importEclipseProject(String projectdir) throws IOException, CoreException {
    Logs.INFO.info("Importing Eclipse project : " + projectdir);
    IWorkspaceRoot root = workspace.getRoot();

    File src = new File(projectdir);
    File dst = new File(root.getLocation().toFile(), src.getName());
    copyDirectory(src, dst);

    final IProject project = workspace.getRoot().getProject(src.getName());
    final List<IProject> addedProjectList = new ArrayList<IProject>();

    workspace.run(new IWorkspaceRunnable() {
      public void run(IProgressMonitor monitor) throws CoreException {
        // create project as java project
        if (!project.exists()) {
          IProjectDescription projectDescription = workspace.newProjectDescription(project.getName());
          projectDescription.setLocation(null);
          project.create(projectDescription, monitor);
          project.open(IResource.NONE, monitor);
        } else {
          project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        }
        addedProjectList.add(project);
      }
    }, workspace.getRoot(), IWorkspace.AVOID_UPDATE, monitor);
    Logs.INFO.info("Eclipse project imported");
    return addedProjectList.get(0);
  }

  private void copyDirectory(File sourceLocation, File targetLocation) throws IOException {
    if (sourceLocation.getName().contains(".svn"))
      return;
    if (sourceLocation.isDirectory()) {
      if (!targetLocation.exists()) {
        targetLocation.mkdir();
      }

      String[] children = sourceLocation.list();
      for (int i = 0; i < children.length; i++) {
        copyDirectory(new File(sourceLocation, children[i]), new File(targetLocation, children[i]));
      }
    } else {

      InputStream in = new FileInputStream(sourceLocation);
      OutputStream out = new FileOutputStream(targetLocation);
      byte[] buf = new byte[1024];
      int len;
      while ((len = in.read(buf)) > 0) {
        out.write(buf, 0, len);
      }
      in.close();
      out.close();
    }
  }

  public static void waitForJobs() throws Exception {
    while (!Job.getJobManager().isIdle()) {
      Thread.sleep(1000);
    }
  }

  protected void cleanMarckerInfo() {
    markerCheckerList = null;
  }

  protected void addMarckerInfo(int priority, long line, String message) {
    if (markerCheckerList == null) {
      markerCheckerList = new ArrayList<MarkerChecker>();
    }
    markerCheckerList.add(new MarkerChecker(priority, line, message));
  }

  protected void assertMarkers(IMarker[] markers) throws CoreException {
    for (IMarker marker : markers) {
      assertMarker(marker);
    }
  }

  protected void assertMarker(IMarker marker) throws CoreException {
    if (Logs.INFO.isDebugEnabled()) {
      Logs.INFO.debug("Checker marker[" + marker.getId() + "] (" + marker.getAttribute(IMarker.PRIORITY) + ") : line " + marker.getAttribute(IMarker.LINE_NUMBER) + " : "
          + marker.getAttribute(IMarker.MESSAGE));
    }
    if (!SonarPlugin.MARKER_ID.equals(marker.getType()))
      return;
    for (MarkerChecker checker : markerCheckerList) {
      if (checker.check(marker))
        return;
    }
    fail("MarckerChecker faild for marker[" + marker.getId() + "] (" + marker.getAttribute(IMarker.PRIORITY) + ") : line " + marker.getAttribute(IMarker.LINE_NUMBER) + " : "
        + marker.getAttribute(IMarker.MESSAGE));
  }

  // =========================================================================
  // == TODO : Use org.sonar.ide.commons.tests ==
  // == Duplicate code from sonar-ide-commons/src/test/java ==

  /**
   * @author Evgeny Mandrikov
   */
  public static class SonarTestServer {
    private ServletTester tester;
    private String        baseUrl;

    public void start() throws Exception {
      tester = new ServletTester();
      tester.setContextPath("/");
      tester.addServlet(VersionServlet.class, "/api/server/version");
      tester.addServlet(ViolationServlet.class, "/api/violations");
      tester.addServlet(SourceServlet.class, "/api/sources");

      baseUrl = tester.createSocketConnector(true);
      tester.start();
    }

    public void stop() throws Exception {
      tester.stop();
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public Sonar getSonar() {
      HttpClient4Connector connector = new HttpClient4Connector(new Host(getBaseUrl()));
      return new Sonar(connector);
    }
  }

}