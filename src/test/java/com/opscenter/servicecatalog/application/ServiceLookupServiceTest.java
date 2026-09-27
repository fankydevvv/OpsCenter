package com.opscenter.servicecatalog.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.servicecatalog.domain.CatalogService;
import com.opscenter.servicecatalog.domain.ServiceEnvironment;
import com.opscenter.servicecatalog.infrastructure.ServiceEnvironmentRepository;
import com.opscenter.servicecatalog.infrastructure.ServiceRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * FR-ALT-05 / D-44 / D-48 / D-52: labels -> normalised key -> cache -> database, including the
 * negative cache and the "environment not registered but still MAPPED" rule.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ServiceLookupServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    @Mock ServiceRepository services;
    @Mock ServiceEnvironmentRepository environments;
    @Mock ServiceResolutionCache cache;

    private ServiceLookupService lookup;
    private CatalogService odoo;

    @BeforeEach
    void setUp() {
        ServiceCatalogProperties properties = new ServiceCatalogProperties(new ServiceCatalogProperties.Resolution(
                List.of("service", "service_name", "app"), List.of("environment", "env"), true, Duration.ofMinutes(10),
                Duration.ofSeconds(30)));
        lookup = new ServiceLookupService(services, environments, cache, properties);
        odoo = CatalogService.create(ORG, "odoo-erp", "Odoo ERP", null, null, null);
        odoo.assignMainOwners(UUID.randomUUID(), null, null);
        when(cache.get(any(), anyString(), any())).thenReturn(Optional.empty());
    }

    @Test
    void keyOf_takesTheFirstNonBlankLabel_andNormalises() {
        assertThat(lookup.keyOf(Map.of("service", "Odoo-ERP", "env", "prod")))
                .isEqualTo(new ServiceKey("odoo-erp", "PRODUCTION"));
        assertThat(lookup.keyOf(Map.of("service", " ", "service_name", "billing", "app", "ignored",
                "environment", "stage", "env", "dev")))
                .isEqualTo(new ServiceKey("billing", "STAGING"));
        assertThat(lookup.keyOf(Map.of("app", "worker"))).isEqualTo(new ServiceKey("worker", null));
        assertThat(lookup.keyOf(Map.of("alertname", "HighCpu"))).isEqualTo(new ServiceKey(null, null));
        assertThat(lookup.keyOf(null)).isEqualTo(new ServiceKey(null, null));
    }

    @Test
    void TC_ALT_005_mappedServiceWithRegisteredEnvironment_isCached() {
        ServiceEnvironment dev = ServiceEnvironment.create(odoo.getId(), "DEV", null, null, null, null, null);
        when(services.findByOrganizationIdAndCodeAndActiveTrue(ORG, "odoo-erp")).thenReturn(Optional.of(odoo));
        when(environments.findByServiceIdAndEnvironmentCodeAndActiveTrue(odoo.getId(), "DEV")).thenReturn(Optional.of(dev));

        ResolvedService resolved = lookup.resolveByLabels(ORG, Map.of("service", "odoo-erp", "environment", "development"));

        assertThat(resolved.mappingStatus()).isEqualTo(MappingStatus.MAPPED);
        assertThat(resolved.serviceId()).isEqualTo(odoo.getId());
        assertThat(resolved.serviceName()).isEqualTo("Odoo ERP");
        assertThat(resolved.owningTeamId()).isEqualTo(odoo.getOwningTeamId());
        assertThat(resolved.serviceEnvironmentId()).isEqualTo(dev.getId());
        assertThat(resolved.environment()).isEqualTo("DEV");
        assertThat(resolved.environmentRegistered()).isTrue();
        verify(cache).put(ORG, "odoo-erp", "DEV",
                new ServiceResolutionCache.Entry(odoo.getId(), dev.getId(), odoo.getOwningTeamId(), "Odoo ERP"));
    }

    @Test
    void unknownEnvironment_staysMapped_butNotRegistered() {
        when(services.findByOrganizationIdAndCodeAndActiveTrue(ORG, "odoo-erp")).thenReturn(Optional.of(odoo));
        when(environments.findByServiceIdAndEnvironmentCodeAndActiveTrue(any(), any())).thenReturn(Optional.empty());

        ResolvedService resolved = lookup.resolve(ORG, "odoo-erp", "PRD");

        assertThat(resolved.mapped()).isTrue();
        assertThat(resolved.environment()).isEqualTo("PRODUCTION");
        assertThat(resolved.environmentRegistered()).isFalse();
    }

    @Test
    void TC_ALT_006_unknownOrInactiveService_isUnmapped_andNegativelyCached() {
        when(services.findByOrganizationIdAndCodeAndActiveTrue(ORG, "ghost")).thenReturn(Optional.empty());

        ResolvedService resolved = lookup.resolve(ORG, "Ghost", "DEV");

        assertThat(resolved.mappingStatus()).isEqualTo(MappingStatus.UNMAPPED);
        assertThat(resolved.serviceCode()).as("code kept for the UI").isEqualTo("ghost");
        verify(cache).put(ORG, "ghost", "DEV", ServiceResolutionCache.Entry.NONE);
    }

    @Test
    void cacheHit_skipsTheDatabase() {
        UUID serviceId = UUID.randomUUID();
        when(cache.get(ORG, "odoo-erp", null))
                .thenReturn(Optional.of(new ServiceResolutionCache.Entry(serviceId, null, null, "Odoo ERP")));

        ResolvedService resolved = lookup.resolve(ORG, "odoo-erp", null);

        assertThat(resolved.serviceId()).isEqualTo(serviceId);
        verifyNoInteractions(services, environments);
        verify(cache, never()).put(any(), any(), any(), any());
    }

    @Test
    void codeThatCannotBeACatalogCode_isUnmappedWithoutAnyLookup() {
        ResolvedService resolved = lookup.resolve(ORG, "Payment API", "prod");

        assertThat(resolved.mapped()).isFalse();
        assertThat(resolved.environment()).isEqualTo("PRODUCTION");
        verifyNoInteractions(services, environments, cache);
        assertThat(lookup.resolve(ORG, null, null).mapped()).isFalse();
    }
}
