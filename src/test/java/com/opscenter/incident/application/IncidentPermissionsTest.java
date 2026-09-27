package com.opscenter.incident.application;

import java.util.Set;

import com.opscenter.incident.domain.IncidentAction;
import com.opscenter.incident.domain.IncidentStatus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code allowedActions} = state machine AND permission (blueprint §7.4, §13). */
class IncidentPermissionsTest {

    private static final Set<String> ENGINEER = Set.of("incident.read", "incident.acknowledge", "incident.investigate",
            "incident.mitigate", "incident.resolve", "incident.close");
    private static final Set<String> COORDINATOR = Set.of("incident.read", "incident.acknowledge", "incident.close");

    @Test
    void engineer_getsTheNextStepOfTheLifecycle() {
        assertThat(IncidentPermissions.allowedActions(IncidentStatus.OPEN, ENGINEER))
                .containsExactly(IncidentAction.ACKNOWLEDGE);
        assertThat(IncidentPermissions.allowedActions(IncidentStatus.REOPENED, ENGINEER))
                .containsExactly(IncidentAction.START_INVESTIGATION);
        assertThat(IncidentPermissions.allowedActions(IncidentStatus.MITIGATED, ENGINEER))
                .containsExactly(IncidentAction.RESOLVE);
        assertThat(IncidentPermissions.allowedActions(IncidentStatus.RESOLVED, ENGINEER)).isEmpty();
    }

    @Test
    void missingPermission_hidesAnActionTheStateMachineWouldAllow() {
        assertThat(IncidentPermissions.allowedActions(IncidentStatus.OPEN, COORDINATOR))
                .containsExactly(IncidentAction.ACKNOWLEDGE);
        assertThat(IncidentPermissions.allowedActions(IncidentStatus.ACKNOWLEDGED, COORDINATOR)).isEmpty();
        assertThat(IncidentPermissions.allowedActions(IncidentStatus.OPEN, Set.of("incident.read"))).isEmpty();
    }

    @Test
    void everyCommand_hasItsOwnPermissionCode() {
        for (IncidentAction action : IncidentAction.values()) {
            assertThat(IncidentPermissions.of(action)).startsWith("incident.");
        }
        assertThat(IncidentPermissions.of(IncidentAction.START_INVESTIGATION)).isEqualTo("incident.investigate");
    }
}
