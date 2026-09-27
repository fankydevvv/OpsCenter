package com.opscenter.servicecatalog.infrastructure;

import com.opscenter.servicecatalog.application.ServiceCatalogProperties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link ServiceCatalogProperties} ({@code opscenter.service-catalog.*}). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ServiceCatalogProperties.class)
public class ServiceCatalogConfig {
}
