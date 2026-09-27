package com.opscenter.shared.infrastructure.status;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import com.opscenter.OpscenterBackendApplication;
import com.opscenter.shared.application.status.ComponentProbe;
import com.opscenter.shared.application.status.ProbeResult;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The backend itself: if this probe runs, the process is up. Reports the build version (the
 * {@code Implementation-Version} of the jar manifest, written by the Spring Boot parent's jar
 * configuration; {@code dev} when running from an IDE), Java version, active profiles and uptime.
 */
@Component
public class BackendProbe implements ComponentProbe {

    private final Environment environment;

    public BackendProbe(Environment environment) {
        this.environment = environment;
    }

    @Override
    public String name() {
        return "backend";
    }

    @Override
    public ProbeResult probe() {
        String version = OpscenterBackendApplication.class.getPackage().getImplementationVersion();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("java", Runtime.version().toString());
        details.put("profiles", Arrays.asList(environment.getActiveProfiles()));
        details.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        return ProbeResult.up(version == null ? "dev" : version, details);
    }
}
