package com.opscenter.incident.domain;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.opscenter.shared.domain.BusinessRuleException;
import com.opscenter.shared.domain.ConflictException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Every (status, command) pair of the Sprint 2 state machine (02-SAD §9, blueprint §8.6, D-55, D-56):
 * 9 statuses x 5 commands = 45 cases - 7 allowed (VERIFIED -> CLOSED only reachable from Sprint 4),
 * 1 guarded with 422 (CLOSE from RESOLVED), 37 rejected with 409.
 */
class IncidentStateMachineTest {

    /** The allowed transitions - the table of blueprint §8.6. */
    private static final Map<IncidentAction, Map<IncidentStatus, IncidentStatus>> ALLOWED = Map.of(
            IncidentAction.ACKNOWLEDGE, Map.of(
                    IncidentStatus.OPEN, IncidentStatus.ACKNOWLEDGED,
                    IncidentStatus.ASSIGNED, IncidentStatus.ACKNOWLEDGED),
            IncidentAction.START_INVESTIGATION, Map.of(
                    IncidentStatus.ACKNOWLEDGED, IncidentStatus.INVESTIGATING,
                    IncidentStatus.REOPENED, IncidentStatus.INVESTIGATING),
            IncidentAction.MITIGATE, Map.of(IncidentStatus.INVESTIGATING, IncidentStatus.MITIGATED),
            IncidentAction.RESOLVE, Map.of(IncidentStatus.MITIGATED, IncidentStatus.RESOLVED),
            IncidentAction.CLOSE, Map.of(IncidentStatus.VERIFIED, IncidentStatus.CLOSED));

    static Stream<Arguments> allowedTransitions() {
        List<Arguments> cases = new ArrayList<>();
        ALLOWED.forEach((action, transitions) ->
                transitions.forEach((from, to) -> cases.add(arguments(from, action, to))));
        return cases.stream();
    }

    static Stream<Arguments> forbiddenTransitions() {
        List<Arguments> cases = new ArrayList<>();
        for (IncidentStatus status : IncidentStatus.values()) {
            for (IncidentAction action : IncidentAction.values()) {
                boolean allowed = ALLOWED.get(action).containsKey(status);
                boolean guarded = action == IncidentAction.CLOSE && status == IncidentStatus.RESOLVED;
                if (!allowed && !guarded) {
                    cases.add(arguments(status, action));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{1} from {0} -> {2}")
    @MethodSource("allowedTransitions")
    void allowedTransition_leadsToItsTargetStatus(IncidentStatus from, IncidentAction action, IncidentStatus to) {
        assertThat(IncidentStateMachine.next(from, action)).isEqualTo(to);
        assertThat(IncidentStateMachine.canApply(from, action)).isTrue();
        assertThat(IncidentStateMachine.allowedActions(from)).contains(action);
    }

    @ParameterizedTest(name = "{1} from {0} is rejected")
    @MethodSource("forbiddenTransitions")
    void forbiddenTransition_is409InvalidTransition(IncidentStatus from, IncidentAction action) {
        assertThatThrownBy(() -> IncidentStateMachine.next(from, action))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).code())
                        .isEqualTo(IncidentErrorCodes.INCIDENT_INVALID_TRANSITION))
                .hasMessageContaining(from.name());
        assertThat(IncidentStateMachine.canApply(from, action)).isFalse();
        assertThat(IncidentStateMachine.allowedActions(from)).doesNotContain(action);
    }

    @Test
    void forbiddenCases_areCountedCompletely() {
        assertThat(allowedTransitions().count()).isEqualTo(7);
        assertThat(forbiddenTransitions().count())
                .isEqualTo((long) IncidentStatus.values().length * IncidentAction.values().length - 7 - 1);
    }

    @Test
    void closeFromResolved_isTheRecoveryVerificationGuard_422() {
        assertThatThrownBy(() -> IncidentStateMachine.next(IncidentStatus.RESOLVED, IncidentAction.CLOSE))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(ex -> assertThat(((BusinessRuleException) ex).code())
                        .isEqualTo(IncidentErrorCodes.RECOVERY_VERIFICATION_REQUIRED));
        // the guard is not an "allowed action": the UI must not offer a button that cannot succeed
        assertThat(IncidentStateMachine.allowedActions(IncidentStatus.RESOLVED)).isEmpty();
    }

    @Test
    void systemReopen_isOnlyPossibleFromResolved() {
        assertThat(IncidentStateMachine.reopen(IncidentStatus.RESOLVED)).isEqualTo(IncidentStatus.REOPENED);
        for (IncidentStatus status : EnumSet.complementOf(EnumSet.of(IncidentStatus.RESOLVED))) {
            assertThatThrownBy(() -> IncidentStateMachine.reopen(status))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining(status.name());
        }
    }

    @ParameterizedTest
    @EnumSource(IncidentStatus.class)
    void openStates_matchTheUniqueIndexDefinition(IncidentStatus status) {
        boolean expectedOpen = status != IncidentStatus.RESOLVED && status != IncidentStatus.VERIFIED
                && status != IncidentStatus.CLOSED;
        assertThat(status.isOpen()).isEqualTo(expectedOpen);
        assertThat(IncidentStatus.openStates().contains(status)).isEqualTo(expectedOpen);
    }
}
