package com.opscenter.incident.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.opscenter.shared.domain.BusinessRuleException;
import com.opscenter.shared.domain.ConflictException;

/**
 * The incident lifecycle of Sprint 2 as one table (02-SAD §9, blueprint §8.6, D-55).
 *
 * <pre>
 *  command               from                    to
 *  ACKNOWLEDGE           OPEN, ASSIGNED          ACKNOWLEDGED
 *  START_INVESTIGATION   ACKNOWLEDGED, REOPENED  INVESTIGATING
 *  MITIGATE              INVESTIGATING           MITIGATED
 *  RESOLVE               MITIGATED               RESOLVED
 *  CLOSE                 VERIFIED                CLOSED      (RESOLVED -> 422 RECOVERY_VERIFICATION_REQUIRED)
 *  (system) reopen       RESOLVED                REOPENED    (an alert of the same group fires again)
 * </pre>
 * Every other combination is {@code 409 INCIDENT_INVALID_TRANSITION} (D-56).
 * <p>
 * Why a table and not {@code if}s spread over services: the rule is then written exactly once,
 * the controller contains none of it, the detail endpoint can compute {@code allowedActions} from
 * the same data the commands are checked against, and a unit test can walk every
 * (status, command) pair. Relaxing the flow later (R-39) is a one-row change.
 * <p>
 * The only deliberate deviation from 02-SAD §9: OPEN -> ACKNOWLEDGED is allowed, because
 * assignment (OPEN -> ASSIGNED) only arrives in Sprint 3 - an engineer acknowledging an OPEN
 * incident effectively takes it.
 */
public final class IncidentStateMachine {

    private record Transition(Set<IncidentStatus> from, IncidentStatus to) {
    }

    private static final Map<IncidentAction, Transition> TRANSITIONS = new EnumMap<>(IncidentAction.class);

    static {
        TRANSITIONS.put(IncidentAction.ACKNOWLEDGE,
                new Transition(EnumSet.of(IncidentStatus.OPEN, IncidentStatus.ASSIGNED), IncidentStatus.ACKNOWLEDGED));
        TRANSITIONS.put(IncidentAction.START_INVESTIGATION,
                new Transition(EnumSet.of(IncidentStatus.ACKNOWLEDGED, IncidentStatus.REOPENED),
                        IncidentStatus.INVESTIGATING));
        TRANSITIONS.put(IncidentAction.MITIGATE,
                new Transition(EnumSet.of(IncidentStatus.INVESTIGATING), IncidentStatus.MITIGATED));
        TRANSITIONS.put(IncidentAction.RESOLVE,
                new Transition(EnumSet.of(IncidentStatus.MITIGATED), IncidentStatus.RESOLVED));
        // Sprint 4 reaches VERIFIED; until then CLOSE never succeeds (see next()).
        TRANSITIONS.put(IncidentAction.CLOSE,
                new Transition(EnumSet.of(IncidentStatus.VERIFIED), IncidentStatus.CLOSED));
    }

    private IncidentStateMachine() {
    }

    /**
     * The status {@code action} leads to from {@code current}.
     *
     * @throws BusinessRuleException 422 {@code RECOVERY_VERIFICATION_REQUIRED} for CLOSE from RESOLVED
     * @throws ConflictException     409 {@code INCIDENT_INVALID_TRANSITION} for every other disallowed pair
     */
    public static IncidentStatus next(IncidentStatus current, IncidentAction action) {
        if (action == IncidentAction.CLOSE && current == IncidentStatus.RESOLVED) {
            // 03-DB §38.4: an incident may only be closed after its recovery was verified (PASSED).
            // The rule exists today even though verification is Sprint 4 - hence 422, not 409.
            throw new BusinessRuleException(IncidentErrorCodes.RECOVERY_VERIFICATION_REQUIRED,
                    "A resolved incident can only be closed after its recovery has been verified "
                            + "(recovery verification arrives in Sprint 4)");
        }
        Transition transition = TRANSITIONS.get(action);
        if (!transition.from().contains(current)) {
            throw new ConflictException(IncidentErrorCodes.INCIDENT_INVALID_TRANSITION,
                    "Cannot " + label(action) + " an incident in status " + current + " (allowed from "
                            + transition.from() + ")");
        }
        return transition.to();
    }

    /** {@code true} when {@link #next} would succeed. */
    public static boolean canApply(IncidentStatus current, IncidentAction action) {
        Transition transition = TRANSITIONS.get(action);
        return transition.from().contains(current);
    }

    /** Commands that would succeed now, in lifecycle order - the base of {@code allowedActions} (04-API §7). */
    public static List<IncidentAction> allowedActions(IncidentStatus current) {
        List<IncidentAction> allowed = new ArrayList<>();
        for (IncidentAction action : IncidentAction.values()) {
            if (canApply(current, action)) {
                allowed.add(action);
            }
        }
        return Collections.unmodifiableList(allowed);
    }

    /**
     * System transition when an alert of the incident's group fires again (01-SRS §7 "REOPENED when
     * the incident re-appears", D-54).
     *
     * @throws ConflictException 409 {@code INCIDENT_INVALID_TRANSITION} unless {@code current} is RESOLVED
     */
    public static IncidentStatus reopen(IncidentStatus current) {
        if (current != IncidentStatus.RESOLVED) {
            throw new ConflictException(IncidentErrorCodes.INCIDENT_INVALID_TRANSITION,
                    "Only a RESOLVED incident can be reopened (current status " + current + ")");
        }
        return IncidentStatus.REOPENED;
    }

    private static String label(IncidentAction action) {
        return action.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
