package com.flow.plugin.spring;

import com.flow.adapter.FlowPlugin;
import com.flow.adapter.Model.GraphModel;
import com.flow.adapter.util.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public class SpringEndpointPlugin implements FlowPlugin {

    private static final Logger log = LoggerFactory.getLogger(SpringEndpointPlugin.class);

    @Override
    public void enrich(GraphModel model, Path srcRoot, ConfigLoader config) {
        try {
            new SpringEndpointScanner(config).scanInto(model, srcRoot);
        } catch (IOException e) {
            // Rethrow as a plain Exception — ScanCommand's plugin runner will catch it,
            // log a WARN, and continue. The scan is NOT aborted.
            throw new RuntimeException("Spring plugin scan failed (partial enrichment possible): " + e.getMessage(), e);
        }
    }
}


