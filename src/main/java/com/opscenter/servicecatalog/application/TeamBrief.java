package com.opscenter.servicecatalog.application;

import java.util.UUID;

import com.opscenter.organization.application.TeamRef;

/** A team as shown inside service DTOs: {@code {id, code, name}} (blueprint §7.1). */
public record TeamBrief(UUID id, String code, String name) {

    public static TeamBrief from(TeamRef team) {
        return team == null ? null : new TeamBrief(team.id(), team.code(), team.name());
    }
}
