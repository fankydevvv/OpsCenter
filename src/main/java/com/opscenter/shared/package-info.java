/**
 * Shared kernel: the technical building blocks every business module relies on.
 * <ul>
 *   <li>{@code api} - HTTP contracts common to all endpoints (error body, pagination).</li>
 *   <li>{@code application} - ports and services used by application layers (current user,
 *       idempotency, outbox appender).</li>
 *   <li>{@code domain} - exception hierarchy and the auditable base entity.</li>
 *   <li>{@code infrastructure} - web filters/advice, security, persistence, messaging, JSON.</li>
 * </ul>
 * The shared kernel never imports a business module; business modules import it.
 */
package com.opscenter.shared;
