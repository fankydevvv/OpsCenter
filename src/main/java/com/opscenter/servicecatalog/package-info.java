/**
 * Service Catalog module (02-SAD §6.3, 01-SRS §4, 03-DB §7/§38.2, 04-API §5; blueprint D-32..D-37, D-52).
 * <ul>
 *   <li>{@code domain} - {@code CatalogService} (aggregate root owning its additional
 *       {@code ServiceOwner}s), {@code ServiceEnvironment} (own aggregate), the code rules
 *       {@code ServiceCode} / {@code EnvironmentCode} (aliases PROD -> PRODUCTION ...), URL rule,
 *       error codes and audit actions.</li>
 *   <li>{@code application} - {@code ServiceCatalogService} (commands: idempotent create, PATCH,
 *       PUT ownership, environments; audit; cache eviction after commit), {@code ServiceQueryService}
 *       (paged list, detail), and the module's <b>public API</b> {@code ServiceLookup}
 *       (alert labels -> service, used by the alert module; batch {@code findRefs} for display).</li>
 *   <li>{@code infrastructure} - Spring Data repositories, specifications and the Redis adapter of
 *       the resolution cache.</li>
 *   <li>{@code api} - {@code /api/v1/services} and {@code /api/v1/service-environments}.</li>
 * </ul>
 * Dependency rule: other modules use only {@code ServiceLookup} and its records; this module reads
 * teams, users and the default organization only through {@code TeamLookup}, {@code UserLookup}
 * and {@code OrganizationLookup} - no entity crosses a module boundary.
 */
package com.opscenter.servicecatalog;
