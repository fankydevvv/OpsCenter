package com.opscenter.organization.domain;

/**
 * Lifecycle of master data rows such as organizations and teams (03-DB §29 "disable / soft
 * delete"). {@code INACTIVE} <em>is</em> the soft delete: the row stays for history and foreign
 * keys, {@code is_active} flips to false and {@code deleted_at} is stamped.
 */
public enum MasterDataStatus {
    ACTIVE,
    INACTIVE
}
