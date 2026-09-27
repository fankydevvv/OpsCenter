package com.opscenter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the OpsCenter backend.
 * <p>
 * The application is a <em>modular monolith</em> (02-SAD §16, 05-DEPLOY §23.2): one deployable,
 * but the code is split into modules ({@code com.opscenter.<module>.{api,application,domain,infrastructure}})
 * so a module can later be extracted into a service without rewriting it. Cross-cutting behaviour
 * (request ids, error model, security, outbox, idempotency) lives in {@code com.opscenter.shared}.
 */
@SpringBootApplication
public class OpscenterBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpscenterBackendApplication.class, args);
    }

}
