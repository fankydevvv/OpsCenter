package com.opscenter.audit.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.opscenter.audit.domain.AuditLog;

import org.springframework.data.repository.Repository;

/**
 * Insert/read access to {@code audit_logs} - and nothing else.
 * <p>
 * {@code audit_logs} is append-only (03-DB §21, §29; TC-AUD-005). Extending {@code JpaRepository}
 * would hand every module {@code delete*()} and update-by-{@code save()} for free, so this
 * interface extends the bare {@link Repository} marker and declares only the methods the
 * application is allowed to call. Tests that need to clean the table do so with SQL, on purpose.
 */
public interface AuditLogRepository extends Repository<AuditLog, UUID> {

    /** Inserts a new row (the entity has an assigned id and is {@code Persistable}). */
    AuditLog save(AuditLog entry);

    Optional<AuditLog> findById(UUID id);

    long count();

    List<AuditLog> findByResourceTypeAndResourceIdOrderByCreatedAtDesc(String resourceType, UUID resourceId);
}
