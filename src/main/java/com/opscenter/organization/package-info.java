/**
 * Organization &amp; Team module (02-SAD §6.2, 03-DB §6, 04-API §5/§21).
 * <ul>
 *   <li>{@code domain} - {@code Organization}, {@code Team} (aggregate root owning its
 *       {@code TeamMember}s), status/member-type enums and error codes.</li>
 *   <li>{@code application} - {@code OrganizationService}, {@code TeamService} (transactions,
 *       audit, idempotent create) and their result DTOs.</li>
 *   <li>{@code infrastructure} - Spring Data repositories and the adapter that answers identity's
 *       {@code TeamMembershipQuery} port.</li>
 *   <li>{@code api} - {@code /api/v1/organizations} and {@code /api/v1/teams} controllers.</li>
 * </ul>
 * Dependency rule: organization -> identity only through user ids and read access to
 * {@code UserRepository} (existence and display names). No entity of this module references a
 * {@code User} entity, so the modules could be split into services later without a shared schema.
 */
package com.opscenter.organization;
