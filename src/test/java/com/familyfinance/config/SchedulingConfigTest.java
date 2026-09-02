package com.familyfinance.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

class SchedulingConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    void schedulingRemainsEnabledByDefault() {
        contextRunner.run(context -> assertThat(context.containsBean(
                TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isTrue());
    }

    @Test
    void schedulingCanBeDisabledForDeterministicAcceptanceRuns() {
        contextRunner.withPropertyValues("app.scheduling.enabled=false")
                .run(context -> assertThat(context.containsBean(
                        TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isFalse());
    }
}
