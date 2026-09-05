package com.familyfinance.extension;

import java.util.List;
import java.util.HashSet;
import java.util.Comparator;
import org.springframework.stereotype.Component;

@Component
public class PluginRegistry {
    private final List<PluginDescriptor> descriptors;

    public PluginRegistry(List<FinancePlugin> plugins) {
        var ids = new HashSet<String>();
        var paths = new HashSet<String>();
        descriptors = plugins.stream().map(FinancePlugin::descriptor)
                .sorted(Comparator.comparing(PluginDescriptor::id)).toList();
        for (var descriptor : descriptors) {
            if (!descriptor.id().matches("[a-z][a-z0-9-]*") || descriptor.apiVersion() != 1
                    || !descriptor.path().equals("/workspace/extensions/" + descriptor.id())
                    || !ids.add(descriptor.id()) || !paths.add(descriptor.path())) {
                throw new IllegalStateException("Invalid or duplicate plugin: " + descriptor.id());
            }
        }
    }

    public List<PluginDescriptor> descriptors() { return descriptors; }
}
