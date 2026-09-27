/**
 * Identity &amp; Access module (02-SAD §6.1, 01-SRS FR-IAM-01..04): local accounts, sessions,
 * refresh tokens, roles and permissions.
 * <ul>
 *   <li>{@code domain} - entities and the rules that live on them ({@code User.lock()},
 *       {@code Role.replacePermissions()}), the {@code PasswordHasher} port, error codes.</li>
 *   <li>{@code application} - use cases and transactions: login/refresh/logout/me, user, role and
 *       permission management. Result DTOs (records) are defined here so the API layer only maps
 *       HTTP requests to commands.</li>
 *   <li>{@code infrastructure} - Spring Data repositories, the JWT issuer, the BCrypt password
 *       hasher and the per-request session validator plugged into the shared security chain.</li>
 *   <li>{@code api} - REST controllers under {@code /api/v1/auth|users|roles|permissions} with
 *       {@code @PreAuthorize} permission codes; no business logic.</li>
 * </ul>
 * Dependency rule: identity imports {@code shared} and {@code audit} only. It must not import
 * {@code organization} (which depends on identity) - team memberships reach this module through
 * the {@code TeamMembershipQuery} port that organization implements.
 */
package com.opscenter.identity;
