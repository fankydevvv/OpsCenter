/**
 * Audit module (01-SRS §15, 03-DB §21): the append-only trail of who changed what.
 * <p>
 * Business modules call {@code AuditRecorder} from inside their transaction; this module never
 * imports a business module. Snapshots are DTOs, never entities, so secrets cannot leak
 * (TC-AUD-004).
 */
package com.opscenter.audit;
