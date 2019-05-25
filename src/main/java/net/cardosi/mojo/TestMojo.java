package net.cardosi.mojo;

import net.cardosi.mojo.cache.CachedProject;
import net.cardosi.mojo.cache.DiskCache;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Mojo(name = "test", requiresDependencyResolution = ResolutionScope.COMPILE)
public class TestMojo extends AbstractGwt3BuildMojo implements ClosureBuildConfiguration {
    /**
     * The dependency scope to use for the classpath.
     * <p>The scope should be one of the scopes defined by org.apache.maven.artifact.Artifact. This includes the following:
     * <ul>
     * <li><i>compile</i> - system, provided, compile
     * <li><i>runtime</i> - compile, runtime
     * <li><i>compile+runtime</i> - system, provided, compile, runtime
     * <li><i>runtime+system</i> - system, compile, runtime
     * <li><i>test</i> - system, provided, compile, runtime, test
     * </ul>
     */
    @Parameter(defaultValue = Artifact.SCOPE_TEST, required = true)
    protected String classpathScope;

    @Parameter(defaultValue = "${project.artifactId}/${project.artifactId}-test.js", required = true)
    protected String initialScriptFilename;

    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}", required = true)
    protected String webappDirectory;

    @Parameter
    protected List<String> externs = new ArrayList<>();

    @Parameter(required = true)
    protected List<String> tests = new ArrayList<>();

    @Parameter(defaultValue = "BUNDLE")
    protected String compilationLevel;

    @Parameter
    protected Map<String, String> defines = new HashMap<>();


    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        PluginDescriptor pluginDescriptor = (PluginDescriptor) getPluginContext().get("pluginDescriptor");
        String pluginVersion = pluginDescriptor.getVersion();

        //TODO need to be very careful about allowing these to be configurable, possibly should tie them to the "plugin version" aspect of the hash
        //     or stitch them into the module's dependencies, that probably makes more sense...
        List<File> extraClasspath = Arrays.asList(
                getFileWithMavenCoords(jreJar),
                getFileWithMavenCoords(internalAnnotationsJar),
                getFileWithMavenCoords(jsinteropAnnotationsJar),
                getFileWithMavenCoords("javax.annotation:jsr250-api:1.0"),
                getFileWithMavenCoords("com.vertispan.jsinterop:base:1.0.0-SNAPSHOT"),//TODO stop hardcoding this when goog releases a "base" which actually works on both platforms
                getFileWithMavenCoords("com.vertispan.j2cl:junit-processor:0.3-SNAPSHOT")
        );

        List<File> extraJsZips = Arrays.asList(
                getFileWithMavenCoords(jreJsZip),
                getFileWithMavenCoords(bootstrapJsZip),
                getFileWithMavenCoords(testJsZip)
        );

        DiskCache diskCache = new DiskCache(pluginVersion, gwt3BuildCacheDir, getFileWithMavenCoords(javacBootstrapClasspathJar), extraClasspath, extraJsZips);
        diskCache.takeLock();
        ProjectBuildingRequest request = new DefaultProjectBuildingRequest(mavenSession.getProjectBuildingRequest());

        // for each project in the reactor, check if it is an app we should compile
        // TODO how do we want to pick which one(s) are actual apps?
        LinkedHashMap<String, CachedProject> projects = new LinkedHashMap<>();

        // if key defines aren't set, assume "prod defaults" - need to doc the heck out of this
        defines.putIfAbsent("jre.checkedMode", "DISABLED");
        defines.putIfAbsent("jre.checks.checkLevel", "MINIMAL");
        defines.putIfAbsent("jsinterop.checks", "DISABLED");

        //scan for test files generated by the processor

        try {
            CachedProject source = loadDependenciesIntoCache(project.getArtifact(), project, false, projectBuilder, request, diskCache, pluginVersion, projects, Artifact.SCOPE_COMPILE_PLUS_RUNTIME, "* ");

            // given that set of tasks, we'll chain one more on the end, and watch _that_ for changes
            List<CachedProject> children = new ArrayList<>(source.getChildren());
            children.add(source);
            CachedProject e = new CachedProject(diskCache, project.getArtifact(), project, children, project.getTestCompileSourceRoots());

            diskCache.release();
            e.registerAsApp(this).join();
        } catch (ProjectBuildingException | IOException e) {
            throw new MojoExecutionException("Failed to build project structure", e);
        }
    }


    @Override
    public String getClasspathScope() {
        return classpathScope;
    }

    @Override
    public List<String> getEntrypoint() {
        return tests.stream().map(c -> "javatests." + c + "_Adapter").collect(Collectors.toList());
    }

    @Override
    public List<String> getExterns() {
        return externs;
    }

    @Override
    public Map<String, String> getDefines() {
        return defines;
    }

    @Override
    public String getWebappDirectory() {
        return webappDirectory;
    }

    @Override
    public String getInitialScriptFilename() {
        return initialScriptFilename;
    }

    @Override
    public String getCompilationLevel() {
        return compilationLevel;
    }
}