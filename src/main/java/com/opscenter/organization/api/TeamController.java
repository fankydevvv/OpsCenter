package com.opscenter.organization.api;

import java.util.UUID;

import jakarta.validation.Valid;

import com.opscenter.organization.application.TeamDetail;
import com.opscenter.organization.application.TeamService;
import com.opscenter.organization.application.TeamSummary;
import com.opscenter.organization.domain.MasterDataStatus;
import com.opscenter.shared.api.PageResponse;
import com.opscenter.shared.application.IdempotentResult;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/teams} (04-API §5, §21). Permission codes per blueprint §7.3; soft delete is
 * {@code PATCH {status: INACTIVE}} - there is intentionally no {@code DELETE /teams/{id}}.
 */
@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

    /** Optional header that makes {@code POST} safe to retry (04-API §2.5, D-13). */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final TeamService teams;

    public TeamController(TeamService teams) {
        this.teams = teams;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('team.read')")
    public PageResponse<TeamSummary> list(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) MasterDataStatus status,
                                          @RequestParam(required = false) UUID organizationId,
                                          Pageable pageable) {
        return PageResponse.from(teams.list(q, status, organizationId, pageable));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('team.create')")
    public ResponseEntity<TeamDetail> create(@Valid @RequestBody CreateTeamRequest request,
                                             @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)
                                             String idempotencyKey) {
        IdempotentResult<TeamDetail> result = teams.create(request.toCommand(), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result.value());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('team.read')")
    public TeamDetail get(@PathVariable UUID id) {
        return teams.get(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('team.update')")
    public TeamDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateTeamRequest request) {
        return teams.update(id, request.toCommand());
    }

    @PostMapping("/{id}/members")
    @PreAuthorize("hasAuthority('team.member.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public TeamDetail addMember(@PathVariable UUID id, @Valid @RequestBody AddTeamMemberRequest request) {
        return teams.addMember(id, request.toCommand());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @PreAuthorize("hasAuthority('team.member.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
        teams.removeMember(id, userId);
    }
}
