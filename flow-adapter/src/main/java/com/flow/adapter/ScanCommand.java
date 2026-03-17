package com.flow.adapter;

import com.flow.adapter.Model.GraphModel;
import com.flow.adapter.Model.GraphModelConverter;
import com.flow.adapter.Model.UnifiedGraphModel;
import com.flow.adapter.scanners.JavaSourceScanner;
import com.flow.adapter.util.ConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "scan", description = "Scan Java sources to produce GEF JSON (methods,endpoints,kafka).")
public class ScanCommand implements Runnable {

  private static final Logger log = LoggerFactory.getLogger(ScanCommand.class);

  @Option(names = "--src", required = true)
  private String src;
  @Option(names = "--config")
  private String configDir;
  @Option(names = "--out")
  private String out;
  @Option(names = "--project", required = true)
  private String projectId;
  @Option(names = "--server", description = "Flow Core Service URL (e.g. http://localhost:8080). If set, publishes graph to the server.")
  private String serverUrl;
  @Option(names = "--api-key", description = "API key for authentication with Flow Core Service")
  private String apiKey;

  @Override
  public void run() {
    // ── 1. Validate source root ───────────────────────────────────────────────
    Path srcRoot = Paths.get(src);
    if (!Files.exists(srcRoot)) {
      // Hard stop — nothing to scan. This is the ONLY case we exit non-zero.
      log.error("[flow-adapter] FATAL: source directory does not exist: {}", srcRoot.toAbsolutePath());
      System.exit(1);
    }

    // ── 2. Load config (optional — missing config is not fatal) ──────────────
    Path cfgPath = Paths.get(configDir != null ? configDir : "src/main/resources");
    ConfigLoader config = new ConfigLoader(cfgPath);  // ConfigLoader already warns internally if missing

    // ── 3. Core source scan ───────────────────────────────────────────────────
    GraphModel model = new GraphModel();
    model.projectId = projectId;
    model.schema = "gef:1.1";

    try {
      new JavaSourceScanner().analyze(model, srcRoot);
      log.info("[flow-adapter] Core scan complete — {} methods, {} endpoints, {} topics, {} calls",
          model.methods.size(), model.endpoints.size(), model.topics.size(), model.calls.size());
    } catch (Exception e) {
      // Core scan failure is fatal — we have nothing to write
      log.error("[flow-adapter] FATAL: core Java source scan failed: {}", e.getMessage(), e);
      System.exit(1);
    }

    // ── 4. Plugin enrichment (each plugin is isolated — one failure != abort) ─
    runPlugins(model, srcRoot, config);

    // ── 5. Write output ───────────────────────────────────────────────────────
    Path outPath = out != null ? Paths.get(out) : Paths.get("flow.json");
    try {
      Path parent = outPath.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      new GraphExporterJson().write(model, outPath);
      log.info("[flow-adapter] Graph written to: {}", outPath.toAbsolutePath());
    } catch (Exception e) {
      log.error("[flow-adapter] FATAL: could not write output file {}: {}", outPath, e.getMessage(), e);
      System.exit(1);
    }

    // ── 6. Publish to FCS (optional — failure is warned, not fatal) ──────────
    if (serverUrl != null && !serverUrl.isBlank()) {
      try {
        UnifiedGraphModel unified = GraphModelConverter.convert(model);
        new GraphPublisher(serverUrl, apiKey).publish(unified);
        log.info("[flow-adapter] Graph published to: {}", serverUrl);
      } catch (Exception e) {
        // Publish failure must NOT fail the build — the flow.json was already written locally.
        // CI pipelines that run without FCS reachable must still succeed.
        log.warn("[flow-adapter] WARN: publish to {} failed (graph was still written locally): {}",
            serverUrl, e.getMessage());
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────
  // Plugin runner — fully isolated, never throws
  // ─────────────────────────────────────────────────────────────────────────────

  private void runPlugins(GraphModel model, Path srcRoot, ConfigLoader config) {
    List<String> loaded   = new ArrayList<>();
    List<String> succeeded = new ArrayList<>();
    List<String> failed   = new ArrayList<>();

    ServiceLoader<FlowPlugin> plugins;
    try {
      plugins = ServiceLoader.load(FlowPlugin.class);
    } catch (Exception e) {
      // ServiceLoader itself failed to initialise — warn but do not abort
      log.warn("[flow-adapter] WARN: could not load plugin registry (ServiceLoader failed): {}. " +
               "Scan will proceed with core results only.", e.getMessage());
      return;
    }

    for (FlowPlugin plugin : plugins) {
      String name = plugin.getClass().getName();
      loaded.add(name);
      try {
        plugin.enrich(model, srcRoot, config);
        succeeded.add(name);
        log.info("[flow-adapter] Plugin OK: {}", name);
      } catch (Exception e) {
        // One broken plugin must NEVER abort the scan.
        // Log full stack at DEBUG so CI logs stay clean; WARN line is always visible.
        failed.add(name);
        log.warn("[flow-adapter] Plugin SKIPPED (error — scan continues): {} → {}", name, e.getMessage());
        log.debug("[flow-adapter] Plugin failure detail for {}", name, e);
      }
    }

    // ── Summary ───────────────────────────────────────────────────────────────
    if (loaded.isEmpty()) {
      log.info("[flow-adapter] No enrichment plugins found on classpath.");
      log.info("[flow-adapter] To add Spring endpoint scanning: include flow-spring-plugin on classpath.");
      log.info("[flow-adapter] To add Kafka topic scanning:    include flow-kafka-plugin on classpath.");
    } else {
      log.info("[flow-adapter] Plugin summary: {} loaded, {} succeeded, {} skipped",
          loaded.size(), succeeded.size(), failed.size());
      if (!failed.isEmpty()) {
        log.warn("[flow-adapter] Skipped plugins (graph is still valid — enrichment was partial):");
        failed.forEach(f -> log.warn("[flow-adapter]   SKIPPED: {}", f));
      }
    }
  }
}