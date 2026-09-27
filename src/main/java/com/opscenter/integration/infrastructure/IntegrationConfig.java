package com.opscenter.integration.infrastructure;

import com.opscenter.integration.application.IntegrationProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link IntegrationProperties} ({@code opscenter.integration.*}). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IntegrationProperties.class)
public class IntegrationConfig {
}
