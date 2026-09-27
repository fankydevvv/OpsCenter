package com.opscenter.servicecatalog.application;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code opscenter.service-catalog.*} (blueprint §11, D-44, D-52).
 *
 * @param resolution how alert labels are turned into a catalog lookup, and how long results are cached
 */
@Validated
@ConfigurationProperties(prefix = "opscenter.service-catalog")
public record ServiceCatalogProperties(@Valid @DefaultValue Resolution resolution) {

    /**
     * @param serviceLabelKeys     label names carrying the service code, first non-blank wins
     * @param environmentLabelKeys label names carrying the environment, first non-blank wins
     * @param cacheEnabled         {@code false} = always read PostgreSQL
     * @param cacheTtl             expiry of a cached resolution (safety net; changes evict explicitly)
     * @param negativeCacheTtl     expiry of a cached "no such service" answer - short, because a stale
     *                             negative entry written right after a catalog change would keep the new
     *                             service's alerts UNMAPPED until it expires (review finding, D-52)
     */
    public record Resolution(
            @NotEmpty @DefaultValue({"service", "service_name", "app"}) List<String> serviceLabelKeys,
            @NotEmpty @DefaultValue({"environment", "env"}) List<String> environmentLabelKeys,
            @DefaultValue("true") boolean cacheEnabled,
            @NotNull @DefaultValue("PT10M") Duration cacheTtl,
            @NotNull @DefaultValue("PT30S") Duration negativeCacheTtl) {
    }
}
