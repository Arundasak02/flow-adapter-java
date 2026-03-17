package com.flow.plugin.kafka;

import com.flow.adapter.FlowPlugin;
import com.flow.adapter.Model.GraphModel;
import com.flow.adapter.util.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public class KafkaPlugin implements FlowPlugin {

    private static final Logger log = LoggerFactory.getLogger(KafkaPlugin.class);

    @Override
    public void enrich(GraphModel model, Path srcRoot, ConfigLoader config) {
        try {
            new KafkaScanner(config).scanInto(model, srcRoot);
        } catch (IOException e) {
            // Rethrow as a plain Exception — ScanCommand's plugin runner will catch it,
            // log a WARN, and continue. The scan is NOT aborted.
            throw new RuntimeException("Kafka plugin scan failed (partial enrichment possible): " + e.getMessage(), e);
        }
    }
}


