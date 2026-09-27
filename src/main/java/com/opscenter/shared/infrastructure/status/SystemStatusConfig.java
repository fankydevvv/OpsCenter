package com.opscenter.shared.infrastructure.status;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link SystemProbeProperties}; the probes themselves are plain {@code @Component}s. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SystemProbeProperties.class)
public class SystemStatusConfig {
}
