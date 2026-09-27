package com.opscenter.identity.application;

/** Input of {@code RoleService.create} ({@code POST /roles}). */
public record CreateRoleCommand(String code, String name, String description) {
}
