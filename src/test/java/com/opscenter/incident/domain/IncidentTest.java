package com.opscenter.incident.domain;

import java.time.Instant;
import java.util.UUID;

import com.opscenter.shared.domain.ConflictException;
import com.opscenter.shared.domain.InvalidRequestException;
import com.opscenter.shared.domain.Severity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rules of the incident aggregate (blueprint D-49, D-54, D-57, D-60). */
class IncidentTest {

    private static final Instant T1 = Instant.parse("2026-09-27T10:00:00Z");
    private static final Instant T2 = T1.plusSeconds(60);
    private static final Instant T3 = T1.plusSeconds(120);

    private static Incident open(boolean unmapped) {
        return Incident.openFromAlert("INC-000042", UUID.randomUUID(), unmapped ? null : UUID.randomUUID(), null,
                "  odoo-erp down  ", null, Severity.P3, IncidentSource.ALERTMANAGER, "DEV", "key", unmapped);
    }

    private static Incident resolvedIncident() {
        Incident incident = open(false);
        incident.acknowledge(T1);
        incident.startInvestigation(T1);
        incident.mitigate(T2, "restarted");
        incident.resolve(T3, "disk full", "cleaned", null);
        return incident;
    }

    @Test
    void newIncident_isOpen_withTrimmedTitle_andUnmappedPrefixWhenNeeded() {
        Incident mapped = open(false);
        assertThat(mapped.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(mapped.getTitle()).isEqualTo("odoo-erp down");
        assertThat(mapped.getOccurrenceCount()).isEqualTo(1);
        assertThat(open(true).getTitle()).isEqualTo("[UNMAPPED] odoo-erp down");
    }

    @Test
    void environmentLongerThanItsColumn_isCut_insteadOfFailingTheInsert() {
        Incident incident = Incident.openFromAlert("INC-000003", UUID.randomUUID(), null, null, "t", null, Severity.P2,
                IncidentSource.ALERTMANAGER, "E".repeat(60), "key", true);
        assertThat(incident.getEnvironment()).hasSize(Incident.ENVIRONMENT_MAX);
    }

    @Test
    void unmappedIncident_adoptsAServiceOnce_andLosesTheUnmappedPrefix() {
        Incident unmapped = open(true);
        UUID service = UUID.randomUUID();
        UUID team = UUID.randomUUID();

        assertThat(unmapped.adoptService(service, team, "DEV")).isTrue();
        assertThat(unmapped.getServiceId()).isEqualTo(service);
        assertThat(unmapped.getOwningTeamId()).isEqualTo(team);
        assertThat(unmapped.getTitle()).isEqualTo("odoo-erp down");
        // never re-assigned automatically, and a mapped incident keeps its service
        assertThat(unmapped.adoptService(UUID.randomUUID(), null, "DEV")).isFalse();
        assertThat(unmapped.getServiceId()).isEqualTo(service);
        assertThat(open(false).adoptService(UUID.randomUUID(), team, "DEV")).isFalse();
    }

    @Test
    void veryLongTitle_isCutToTheColumnLength() {
        Incident incident = Incident.openFromAlert("INC-000001", UUID.randomUUID(), null, null, "x".repeat(900), null,
                Severity.P1, IncidentSource.ALERTMANAGER, null, "key", true);
        assertThat(incident.getTitle()).hasSize(Incident.TITLE_MAX).startsWith("[UNMAPPED] ");
    }

    @Test
    void commands_setTheirTimestamps_andReturnThePreviousStatus() {
        Incident incident = open(false);
        assertThat(incident.acknowledge(T1)).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.startInvestigation(T2)).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        assertThat(incident.mitigate(T2, " restarted ")).isEqualTo(IncidentStatus.INVESTIGATING);
        assertThat(incident.resolve(T3, "disk full", "cleaned", "rotated logs")).isEqualTo(IncidentStatus.MITIGATED);

        assertThat(incident.getAcknowledgedAt()).isEqualTo(T1);
        assertThat(incident.getInvestigatingAt()).isEqualTo(T2);
        assertThat(incident.getMitigatedAt()).isEqualTo(T2);
        assertThat(incident.getResolvedAt()).isEqualTo(T3);
        assertThat(incident.getMitigationSummary()).isEqualTo("rotated logs");
        assertThat(incident.getRootCause()).isEqualTo("disk full");
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
    }

    @Test
    void resolve_requiresRootCauseAndResolution() {
        Incident incident = open(false);
        incident.acknowledge(T1);
        incident.startInvestigation(T1);
        incident.mitigate(T2, "m");

        assertThatThrownBy(() -> incident.resolve(T3, " ", "fix", null)).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("rootCause");
        assertThatThrownBy(() -> incident.resolve(T3, "cause", null, null)).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("resolution");
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.MITIGATED);
    }

    @Test
    void reopenBySystem_clearsResolvedAt_butKeepsTheFirstAcknowledgement() {
        Incident incident = resolvedIncident();

        IncidentStatus previous = incident.reopenBySystem(T3.plusSeconds(10));

        assertThat(previous).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.REOPENED);
        assertThat(incident.getReopenedAt()).isEqualTo(T3.plusSeconds(10));
        assertThat(incident.getResolvedAt()).isNull();
        assertThat(incident.getAcknowledgedAt()).isEqualTo(T1);
        // worked again: acknowledged_at / investigating_at keep their first value (stable MTTA, D-60)
        incident.startInvestigation(T3.plusSeconds(20));
        assertThat(incident.getInvestigatingAt()).isEqualTo(T1);
    }

    @Test
    void severity_isOnlyEverRaised() {
        Incident incident = open(false);
        assertThat(incident.raiseSeverity(Severity.P4)).isFalse();
        assertThat(incident.raiseSeverity(Severity.P3)).isFalse();
        assertThat(incident.getSeverity()).isEqualTo(Severity.P3);
        assertThat(incident.raiseSeverity(Severity.P1)).isTrue();
        assertThat(incident.getSeverity()).isEqualTo(Severity.P1);
        assertThat(incident.raiseSeverity(null)).isFalse();
    }

    @Test
    void staleVersion_isTheIncidentSpecificConflictCode() {
        Incident incident = open(false);
        incident.checkVersion(0);
        assertThatThrownBy(() -> incident.checkVersion(3))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).code())
                        .isEqualTo(IncidentErrorCodes.INCIDENT_VERSION_CONFLICT));
    }

    @Test
    void illegalCommand_leavesTheIncidentUnchanged() {
        Incident incident = open(false);
        assertThatThrownBy(() -> incident.mitigate(T1, "x")).isInstanceOf(ConflictException.class);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getMitigatedAt()).isNull();
        assertThat(incident.getMitigationSummary()).isNull();
    }
}
