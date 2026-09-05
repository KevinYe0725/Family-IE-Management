package com.familyfinance.extension;

import java.util.List;

public record PluginDescriptor(String id, String version, int apiVersion, String name,
        String description, String path, List<String> capabilities) {}
