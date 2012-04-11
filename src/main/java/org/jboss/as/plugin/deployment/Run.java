package org.jboss.as.plugin.deployment;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.plugin.deployment.common.AbstractServerConnection;
import org.jboss.as.plugin.deployment.domain.DomainDeployment;
import org.jboss.as.plugin.deployment.standalone.StandaloneDeployment;
import org.jboss.dmr.ModelNode;
import org.sonatype.aether.RepositorySystem;
import org.sonatype.aether.RepositorySystemSession;
import org.sonatype.aether.repository.RemoteRepository;
import org.sonatype.aether.resolution.ArtifactRequest;
import org.sonatype.aether.resolution.ArtifactResolutionException;
import org.sonatype.aether.resolution.ArtifactResult;
import org.sonatype.aether.util.artifact.DefaultArtifact;

/**
 * @author Stuart Douglas
 * @goal run
 * @requiresDependencyResolution runtime
 */
public class Run extends AbstractServerConnection {

    public static final String JBOSS_AS_ARTIFACT_ID = "jboss-as-dist";
    public static final String JBOSS_AS_GROUP_ID = "org.jboss.as";

    public static final String PLUGIN_GROUP_ID = "org.jboss.as.plugins";
    public static final String PLUGIN_ARTIFACT_ID = "jboss-as-maven-plugin";

    private static final String CONFIG_PATH = "/standalone/configuration/";

    public static final String JBOSS_DIR = "jboss-as-run";

    /**
     * @parameter default-value="${project}"
     * @readonly
     * @required
     */
    private MavenProject project;

    /**
     * The entry point to Aether, i.e. the component doing all the work.
     *
     * @component
     */
    private RepositorySystem repoSystem;

    /**
     * The current repository/network configuration of Maven.
     *
     * @parameter default-value="${repositorySystemSession}"
     * @readonly
     */
    private RepositorySystemSession repoSession;

    /**
     * The project's remote repositories
     *
     * @parameter default-value="${project.remotePluginRepositories}"
     * @readonly
     */
    private List<RemoteRepository> remoteRepos;

    /**
     * @parameter
     */
    private String jbossHome;

    /**
     * @parameter default-value="org.jboss.as:jboss-as-dist:zip:7.1.1.Final"
     */
    private String jbossAsGav;

    /**
     * @parameter
     */
    private String modulePath;

    /**
     * @parameter
     */
    private String bundlePath;

    /**
     * @parameter
     */
    private String jvmArgs;

    /**
     * @parameter
     */
    private String javaHome;

    /**
     * @parameter
     */
    private String serverConfig;

    /**
     * @parameter default-value=60
     */
    private long startupTimeout;

    private ModelControllerClient modelControllerClient;

    private Process process;
    private Thread shutdownThread;

    @Override
    public String goal() {
        return "run";
    }

    public void execute() throws MojoExecutionException, MojoFailureException {
        String jbossHome = extractIfRequired();
        try {
            File jbossHomeDir = new File(jbossHome).getCanonicalFile();
            if (jbossHomeDir.isDirectory() == false)
                throw new IllegalStateException("Cannot find: " + jbossHomeDir);

            String modulesPath = this.modulePath;
            if (modulesPath == null || modulesPath.isEmpty()) {
                modulesPath = jbossHome + File.separatorChar + "modules";
            }

            String bundlesPath = bundlePath;
            if (bundlesPath == null || bundlesPath.isEmpty()) {
                bundlesPath = jbossHome + File.separatorChar + "bundles";
            }

            final String additionalJavaOpts = this.jvmArgs;

            File modulesJar = new File(jbossHome + File.separatorChar + "jboss-modules.jar");
            if (!modulesJar.exists())
                throw new IllegalStateException("Cannot find: " + modulesJar);

            List<String> cmd = new ArrayList<String>();
            String javaHome;
            if (this.javaHome == null) {
                javaHome = System.getenv("JAVA_HOME");
            } else {
                javaHome = this.javaHome;
            }
            String javaExec = javaHome + File.separatorChar + "bin" + File.separatorChar + "java";
            if (javaHome.contains(" ")) {
                javaExec = "\"" + javaExec + "\"";
            }
            cmd.add(javaExec);
            if (additionalJavaOpts != null) {
                for (String opt : additionalJavaOpts.split("\\s+")) {
                    cmd.add(opt);
                }
            }

            cmd.add("-Djboss.home.dir=" + jbossHome);
            cmd.add("-Dorg.jboss.boot.log.file=" + jbossHome + "/standalone/log/boot.log");
            cmd.add("-Dlogging.configuration=file:" + jbossHome + CONFIG_PATH + "logging.properties");
            cmd.add("-Djboss.modules.dir=" + modulesPath);
            cmd.add("-Djboss.bundles.dir=" + bundlesPath);
            cmd.add("-jar");
            cmd.add(modulesJar.getAbsolutePath());
            cmd.add("-mp");
            cmd.add(modulesPath);
            cmd.add("-jaxpmodule");
            cmd.add("javax.xml.jaxp-provider");
            cmd.add("org.jboss.as.standalone");
            if (serverConfig != null) {
                cmd.add("-server-config");
                cmd.add(serverConfig);
            }

            getLog().info("Starting container with: " + cmd.toString());
            ProcessBuilder processBuilder = new ProcessBuilder(cmd);
            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();
            new Thread(new ConsoleConsumer()).start();
            final Process proc = process;
            shutdownThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    if (proc != null) {
                        proc.destroy();
                        try {
                            proc.waitFor();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            });
            Runtime.getRuntime().addShutdownHook(shutdownThread);

            long timeout = startupTimeout * 1000;
            boolean serverAvailable = false;
            long sleep = 50;
            modelControllerClient = ModelControllerClient.Factory.create("localhost", 9999);
            while (timeout > 0 && serverAvailable == false) {
                serverAvailable = isServerInRunningState();
                if (!serverAvailable) {
                    if (processHasDied(proc))
                        break;
                    Thread.sleep(sleep);
                    timeout -= sleep;
                    sleep = Math.max(sleep / 2, 100);
                }
            }
            if (!serverAvailable) {
                destroyProcess();
                throw new MojoExecutionException(String.format("Managed server was not started within [%d] s", startupTimeout));
            }

            Deployment deployment = null;
            final String deploymentName = project.getBuild().getFinalName() + "." + project.getPackaging();
            final File file = new File(project.getBuild().getDirectory() + File.separator + deploymentName);
            try {
                if (isDomainServer()) {
                    deployment = DomainDeployment.create(this, getDomain(), file, deploymentName, Deployment.Type.DEPLOY);
                } else {
                    deployment = StandaloneDeployment.create(this, file, deploymentName, Deployment.Type.DEPLOY);
                }
                deployment.execute();
                deployment.close();
            } finally {
                safeClose(deployment);
            }
            while (true) {
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            throw new MojoExecutionException("starting server failed", e);
        }

    }

    public boolean isServerInRunningState() {
        try {
            ModelNode op = new ModelNode();
            op.get("address").set(new ModelNode());
            op.get("operation").set("read-attribute");
            op.get("name").set("server-state");

            ModelNode rsp = modelControllerClient.execute(op);
            return "success".equals(rsp.get("outcome").asString())
                    && !"STARTING".toString().equals(rsp.get("result").asString())
                    && !"STOPPING".toString().equals(rsp.get("result").asString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String extractIfRequired() throws MojoFailureException, MojoExecutionException {
        if (jbossHome != null) {
            //we do not need to download JBoss
            return jbossHome;
        }
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(new DefaultArtifact(jbossAsGav));
        request.setRepositories(remoteRepos);
        getLog().info("Resolving artifact " + jbossAsGav + " from " + remoteRepos);
        ArtifactResult result;
        try {
            result = repoSystem.resolveArtifact(repoSession, request);
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        final String[] parts = jbossAsGav.split(":");
        final String version = parts[parts.length - 1];

        final char buff[] = new char[1024];
        final File target = new File(project.getBuild().getDirectory() + File.separatorChar + JBOSS_DIR);
        if (target.exists()) {
            target.delete();
        }
        ZipFile file = null;
        try {
            file = new ZipFile(result.getArtifact().getFile());
            final Enumeration<? extends ZipEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                final File extractTarget = new File(target.getAbsolutePath() + File.separator + entry.getName());
                if (entry.isDirectory()) {
                    extractTarget.mkdirs();
                } else {
                    final File parent = new File(extractTarget.getParent());
                    parent.mkdirs();
                    final BufferedReader in = new BufferedReader(new InputStreamReader(file.getInputStream(entry)));
                    try {
                        final BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(extractTarget)));
                        try {
                            int read = 0;
                            while ((read = in.read(buff)) != -1) {
                                out.write(buff, 0, read);
                            }
                        } finally {
                            out.close();
                        }
                    } finally {
                        in.close();
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (file != null) {
                try {
                    file.close();
                } catch (IOException e) {

                }
            }
        }
        return target.getAbsolutePath() + File.separatorChar + "jboss-as-" + version;
    }


    private int destroyProcess() {
        if (process == null)
            return 0;
        process.destroy();
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean processHasDied(final Process process) {
        try {
            process.exitValue();
            return true;
        } catch (IllegalThreadStateException e) {
            // good
            return false;
        }
    }

    /**
     * Runnable that consumes the output of the process. If nothing consumes the output the AS will hang on some platforms
     *
     * @author Stuart Douglas
     */
    private class ConsoleConsumer implements Runnable {

        @Override
        public void run() {
            final InputStream stream = process.getInputStream();
            final BufferedReader reader = new BufferedReader(new InputStreamReader(stream));

            String line = null;
            try {
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            } catch (IOException e) {
            }
        }

    }
}
