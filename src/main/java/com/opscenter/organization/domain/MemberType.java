package com.opscenter.organization.domain;

/**
 * How a user belongs to a team (03-DB §39.2 {@code team_members.member_type}). Incident routing
 * in a later sprint prefers {@code PRIMARY} members, falls back to {@code SECONDARY}, and uses
 * {@code ON_CALL} for the rotation. This is an operational grouping, not an RBAC role.
 */
public enum MemberType {
    PRIMARY,
    SECONDARY,
    ON_CALL
}
