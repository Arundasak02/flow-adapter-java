package com.flow.plugin.maven;

import com.flow.adapter.FlowPlugin;
import com.flow.adapter.GraphExporterJson;
import com.flow.adapter.GraphMetadataUtil;
import com.flow.adapter.Model.GraphModel;
import com.flow.adapter.Model.GraphModelConverter;
import com.flow.adapter.Model.UnifiedGraphModel;
import com.flow.adapter.scanners.JavaSourceScanner;
import com.flow.adapter.util.ConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

@Mojo(name = "scan", defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true)
public class FlowScanMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(defaultValue = "${project.build.sourceDirectory}")
  private String sourceDirectory;

  @Parameter(defaultValue = "${project.build.outputDirectory}")
  private String outputDirectory;

  @Parameter(defaultValue = "${project.basedir}/src/main/resources")
  private String configDirectory;

  @Parameter(property = "flow.graphId", defaultValue = "${project.artifactId}")
  private String graphId;

  @Parameter(property = "flow.version", defaultValue = "${project.version}")
  private String graphVersion;

  @Parameter(property = "flow.packages")
  private String packages;

  @Parameter(property = "flow.writeProjectCopy", defaultValue = "true")
  private boolean writeProjectCopy;

  @Parameter(property = "flow.skip", defaultValue = "false")
  private boolean skip;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (skip) {
      getLog().info("[flow-maven-plugin] Skipping scan (flow.skip=true)");
      return;
    }

    Path srcRoot = Path.of(sourceDirectory);
    if (!Files.exists(srcRoot)) {
      throw new MojoFailureException("Source directory does not exist: " + srcRoot.toAbsolutePath());
    }

    getLog().info("[flow-maven-plugin] Starting static graph scan");
    getLog().info("[flow-maven-plugin] graphId=" + graphId + ", version=" + graphVersion);
    if (packages != null && !packages.isBlank()) {
      getLog().info("[flow-maven-plugin] packages=" + packages + " (informational)");
    }

    ConfigLoader config = new ConfigLoader(Path.of(configDirectory));
    GraphModel model = new GraphModel();
    model.projectId = graphId;
    model.schema = "gef:1.1";

    try {
      new JavaSourceScanner().analyze(model, srcRoot);
    } catch (Exception e) {
      throw new MojoExecutionException("Core Java source scan failed: " + e.getMessage(), e);
    }

    runPlugins(model, srcRoot, config);

    UnifiedGraphModel unified = GraphModelConverter.convert(model);
    unified.graphId = graphId;
    unified.version = graphVersion;
    String adapterVersion = project.getVersion();
    GraphMetadataUtil.enrich(unified, project.getBasedir().toPath(), adapterVersion);

    Path bundledOutput = Path.of(outputDirectory, "META-INF", "flow", "flow.json");
    writeGraph(unified, bundledOutput);
    getLog().info("[flow-maven-plugin] Bundled graph at " + bundledOutput.toAbsolutePath());

    if (writeProjectCopy) {
      Path rootCopy = Path.of(project.getBasedir().getAbsolutePath(), "flow.json");
      writeGraph(unified, rootCopy);
      getLog().info("[flow-maven-plugin] Wrote project copy at " + rootCopy.toAbsolutePath());
    }
  }

  private void runPlugins(GraphModel model, Path srcRoot, ConfigLoader config) {
    List<String> loaded = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    ServiceLoader<FlowPlugin> plugins = ServiceLoader.load(FlowPlugin.class);
    for (FlowPlugin plugin : plugins) {
      String name = plugin.getClass().getName();
      loaded.add(name);
      try {
        plugin.enrich(model, srcRoot, config);
      } catch (Exception e) {
        failed.add(name);
        getLog().warn("[flow-maven-plugin] Plugin failed and was skipped: " + name + " -> " + e.getMessage());
      }
    }
    if (loaded.isEmpty()) {
      getLog().info("[flow-maven-plugin] No enrichment plugins discovered");
      return;
    }
    getLog().info(
        "[flow-maven-plugin] Plugin summary: loaded=" + loaded.size() + ", failed=" + failed.size());
  }

  private void writeGraph(UnifiedGraphModel unified, Path outPath) throws MojoExecutionException {
    try {
      Path parent = outPath.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      new GraphExporterJson().writeUnified(unified, outPath);
    } catch (IOException e) {
      throw new MojoExecutionException("Could not write graph to " + outPath.toAbsolutePath(), e);
    }
  }
}
