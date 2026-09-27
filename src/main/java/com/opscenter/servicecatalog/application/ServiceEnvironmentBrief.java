package com.opscenter.servicecatalog.application;

import java.util.UUID;

import com.opscenter.servicecatalog.domain.ServiceEnvironment;
import com.opscenter.servicecatalog.domain.ServiceStatus;

/** Environment tag inside a list row: {@code {id, environmentCode, status}} (blueprint §7.1). */
public record ServiceEnvironmentBrief(UUID id, String environmentCode, ServiceStatus status) {

    public static ServiceEnvironmentBrief from(ServiceEnvironment environment) {
        return new ServiceEnvironmentBrief(environment.getId(), environment.getEnvironmentCode(), environment.getStatus());
    }
}
