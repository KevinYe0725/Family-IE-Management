package com.familyfinance.extension;

/** Trusted, build-time extensions. Descriptors advertise capabilities, not security grants. */
public interface FinancePlugin {
    PluginDescriptor descriptor();
}
