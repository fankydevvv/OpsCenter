package com.opscenter.alert.infrastructure;

import com.opscenter.alert.application.AlertProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link AlertProperties} ({@code opscenter.alert.*}). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AlertProperties.class)
public class AlertConfig {
}
