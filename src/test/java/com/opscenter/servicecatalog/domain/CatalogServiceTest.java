package com.opscenter.servicecatalog.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.opscenter.shared.domain.InvalidRequestException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Aggregate rules of {@link CatalogService} (D-33..D-36) without any database. */
class CatalogServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    private static CatalogService newService() {
        return CatalogService.create(ORG, "payment-api", " Payment API ", "  ", null, null);
    }

    @Test
    void create_defaultsToActive_trimsName_andBlankDescriptionIsNull() {
        CatalogService service = newService();

        assertThat(service.getName()).isEqualTo("Payment API");
        assertThat(service.getDescription()).isNull();
        assertThat(service.getStatus()).isEqualTo(ServiceStatus.ACTIVE);
        assertThat(service.isActive()).isTrue();
        assertThat(service.getId()).isNotNull();
        assertThatThrownBy(() -> CatalogService.create(ORG, "Not Normalised", "x", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void primaryAndBackupOwnerMustDiffer() {
        CatalogService service = newService();
        UUID user = UUID.randomUUID();

        assertThatThrownBy(() -> service.assignMainOwners(null, user, user))
                .isInstanceOf(InvalidRequestException.class)
                .extracting("code").isEqualTo(ServiceCatalogErrorCodes.SERVICE_OWNERS_MUST_DIFFER);
        service.assignMainOwners(null, user, null);
        assertThat(service.getPrimaryOwnerId()).isEqualTo(user);
        assertThat(service.getBackupOwnerId()).isNull();
    }

    @Test
    void additionalOwner_needsExactlyOneTarget() {
        assertThatThrownBy(() -> new ServiceOwnerSpec(null, null, OwnershipType.TECHNICAL_OWNER, 1))
                .isInstanceOf(InvalidRequestException.class)
                .extracting("code").isEqualTo(ServiceCatalogErrorCodes.SERVICE_OWNER_TARGET_INVALID);
        assertThatThrownBy(() -> new ServiceOwnerSpec(UUID.randomUUID(), UUID.randomUUID(), OwnershipType.TECHNICAL_OWNER, 1))
                .isInstanceOf(InvalidRequestException.class)
                .extracting("code").isEqualTo(ServiceCatalogErrorCodes.SERVICE_OWNER_TARGET_INVALID);
    }

    @Test
    void additionalOwners_areReplacedAsAWhole_andDuplicatesRejected() {
        CatalogService service = newService();
        UUID team = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        service.replaceAdditionalOwners(List.of(
                new ServiceOwnerSpec(team, null, OwnershipType.SUPPORTING_TEAM, 2),
                new ServiceOwnerSpec(null, user, OwnershipType.ON_CALL_CONTACT, 1)));
        assertThat(service.getOwners()).extracting(ServiceOwner::getOwnershipType)
                .containsExactly(OwnershipType.SUPPORTING_TEAM, OwnershipType.ON_CALL_CONTACT);

        // same target with another type is fine, same target + same type is a duplicate
        service.replaceAdditionalOwners(List.of(new ServiceOwnerSpec(null, user, OwnershipType.TECHNICAL_OWNER, 1),
                new ServiceOwnerSpec(null, user, OwnershipType.BUSINESS_OWNER, 1)));
        assertThat(service.getOwners()).hasSize(2).allMatch(o -> user.equals(o.getUserId()));
        assertThatThrownBy(() -> service.replaceAdditionalOwners(List.of(
                new ServiceOwnerSpec(team, null, OwnershipType.SUPPORTING_TEAM, 1),
                new ServiceOwnerSpec(team, null, OwnershipType.SUPPORTING_TEAM, 5))))
                .isInstanceOf(InvalidRequestException.class)
                .extracting("code").isEqualTo(ServiceCatalogErrorCodes.SERVICE_OWNER_DUPLICATE);

        service.replaceAdditionalOwners(List.of());
        assertThat(service.getOwners()).isEmpty();
    }

    @Test
    void replacingOwners_keepsUnchangedRows_updatesPriority_andChangesTheSignature() {
        CatalogService service = newService();
        UUID user = UUID.randomUUID();
        UUID team = UUID.randomUUID();
        service.replaceAdditionalOwners(List.of(new ServiceOwnerSpec(null, user, OwnershipType.ON_CALL_CONTACT, 1),
                new ServiceOwnerSpec(team, null, OwnershipType.SUPPORTING_TEAM, 1)));
        UUID keptRowId = service.getOwners().stream().filter(o -> user.equals(o.getUserId())).findFirst().orElseThrow().getId();
        String signature = service.ownershipSignature();

        // same entries again: nothing changes, not even the row ids (no delete + insert of the same key)
        service.replaceAdditionalOwners(List.of(new ServiceOwnerSpec(team, null, OwnershipType.SUPPORTING_TEAM, 1),
                new ServiceOwnerSpec(null, user, OwnershipType.ON_CALL_CONTACT, 1)));
        assertThat(service.ownershipSignature()).isEqualTo(signature);

        // priority-only change keeps the row and changes the signature; dropping the team removes its row
        service.replaceAdditionalOwners(List.of(new ServiceOwnerSpec(null, user, OwnershipType.ON_CALL_CONTACT, 7)));
        assertThat(service.getOwners()).singleElement().satisfies(owner -> {
            assertThat(owner.getId()).isEqualTo(keptRowId);
            assertThat(owner.getPriority()).isEqualTo(7);
        });
        assertThat(service.ownershipSignature()).isNotEqualTo(signature);
    }

    @Test
    void deactivate_isTheSoftDelete_andActivateUndoesIt() {
        CatalogService service = newService();
        Instant now = Instant.parse("2026-09-27T10:00:00Z");

        service.deactivate(now);
        assertThat(service.isActive()).isFalse();
        assertThat(service.getDeletedAt()).isEqualTo(now);
        service.deactivate(now.plusSeconds(60));
        assertThat(service.getDeletedAt()).as("second deactivate keeps the first timestamp").isEqualTo(now);

        service.activate();
        assertThat(service.isActive()).isTrue();
        assertThat(service.getDeletedAt()).isNull();
    }

    @Test
    void environment_requiresCanonicalCode_andValidUrls() {
        UUID serviceId = UUID.randomUUID();
        ServiceEnvironment environment = ServiceEnvironment.create(serviceId, "PRODUCTION", null,
                "https://pay.example.com/health", null, "", null);

        assertThat(environment.getStatus()).isEqualTo(ServiceStatus.ACTIVE);
        assertThat(environment.getDashboardUrl()).isNull();
        assertThatThrownBy(() -> ServiceEnvironment.create(serviceId, "prod", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> environment.changeDashboardUrl("javascript:alert(1)"))
                .isInstanceOf(InvalidRequestException.class);
        environment.changeHealthEndpoint("");
        assertThat(environment.getHealthEndpoint()).isNull();
    }
}
