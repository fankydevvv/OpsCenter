package com.opscenter.incident.application;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.opscenter.incident.domain.IncidentAction;
import com.opscenter.incident.domain.IncidentStateMachine;
import com.opscenter.incident.domain.IncidentStatus;

/**
 * Permission codes of the incident endpoints (V011, blueprint §6) and the mapping command ->
 * permission used to compute {@code allowedActions}. The controller's {@code @PreAuthorize}
 * expressions use the same strings.
 */
public final class IncidentPermissions {

    public static final String READ = "incident.read";
    public static final String ACKNOWLEDGE = "incident.acknowledge";
    public static final String INVESTIGATE = "incident.investigate";
    public static final String MITIGATE = "incident.mitigate";
    public static final String RESOLVE = "incident.resolve";
    public static final String CLOSE = "incident.close";
    /** Needed for the alert blocks of the Operations Center summary. */
    public static final String ALERT_READ = "alert.read";

    private static final Map<IncidentAction, String> BY_ACTION = new EnumMap<>(IncidentAction.class);

    static {
        BY_ACTION.put(IncidentAction.ACKNOWLEDGE, ACKNOWLEDGE);
        BY_ACTION.put(IncidentAction.START_INVESTIGATION, INVESTIGATE);
        BY_ACTION.put(IncidentAction.MITIGATE, MITIGATE);
        BY_ACTION.put(IncidentAction.RESOLVE, RESOLVE);
        BY_ACTION.put(IncidentAction.CLOSE, CLOSE);
    }

    private IncidentPermissions() {
    }

    public static String of(IncidentAction action) {
        return BY_ACTION.get(action);
    }

    /**
     * State machine AND permission: a button appears only if the command would pass both the
     * {@code @PreAuthorize} check and the transition check (04-API §7, blueprint §13).
     */
    public static List<IncidentAction> allowedActions(IncidentStatus status, Collection<String> callerPermissions) {
        return IncidentStateMachine.allowedActions(status).stream()
                .filter(action -> callerPermissions.contains(of(action)))
                .toList();
    }
}
