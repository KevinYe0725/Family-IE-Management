package com.familyfinance.extension;

import com.familyfinance.shared.ApiEnvelope;
import com.familyfinance.shared.CurrentHousehold;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PluginController {
    private final PluginRegistry registry;
    private final CurrentHousehold household;

    public PluginController(PluginRegistry registry, CurrentHousehold household) {
        this.registry = registry;
        this.household = household;
    }

    @GetMapping("/api/plugins")
    public ApiEnvelope<List<PluginDescriptor>> list(Authentication authentication) {
        household.id(authentication);
        return ApiEnvelope.data(registry.descriptors());
    }
}
