package com.opscenter.organization.domain;

/** Error codes of the organization module (04-API §17 naming {@code DOMAIN_REASON}). */
public final class OrganizationErrorCodes {

    public static final String ORGANIZATION_NOT_FOUND = "ORGANIZATION_NOT_FOUND";
    public static final String ORGANIZATION_INACTIVE = "ORGANIZATION_INACTIVE";

    public static final String TEAM_NOT_FOUND = "TEAM_NOT_FOUND";
    public static final String TEAM_CODE_TAKEN = "TEAM_CODE_TAKEN";
    public static final String TEAM_INACTIVE = "TEAM_INACTIVE";
    public static final String TEAM_MEMBER_EXISTS = "TEAM_MEMBER_EXISTS";
    public static final String TEAM_MEMBER_NOT_FOUND = "TEAM_MEMBER_NOT_FOUND";

    private OrganizationErrorCodes() {
    }
}
