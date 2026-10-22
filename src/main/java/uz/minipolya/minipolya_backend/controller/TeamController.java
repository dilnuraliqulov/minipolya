package uz.minipolya.minipolya_backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.AddTeamMemberRequest;
import uz.minipolya.minipolya_backend.dto.request.CreateTeamRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.TeamDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.TeamResponse;
import uz.minipolya.minipolya_backend.service.TeamService;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    @PostMapping("/api/teams")
    public ApiResponse<TeamDetailResponse> create(@AuthenticationPrincipal UUID userId,
                                                  @Valid @RequestBody CreateTeamRequest request) {
        return ApiResponse.ok(teamService.create(userId, request), "Team created");
    }

    @GetMapping("/api/teams/me")
    public ApiResponse<List<TeamResponse>> listMine(@AuthenticationPrincipal UUID userId) {
        return ApiResponse.ok(teamService.listMine(userId));
    }

    @GetMapping("/api/teams/{id}")
    public ApiResponse<TeamDetailResponse> getDetail(@PathVariable UUID id) {
        return ApiResponse.ok(teamService.getDetail(id));
    }

    @PostMapping("/api/teams/{id}/members")
    public ApiResponse<TeamDetailResponse> addMember(@AuthenticationPrincipal UUID userId,
                                                     @PathVariable UUID id,
                                                     @Valid @RequestBody AddTeamMemberRequest request) {
        return ApiResponse.ok(teamService.addMember(userId, id, request), "Member added");
    }

    @DeleteMapping("/api/teams/{id}/members/{userId}")
    public ApiResponse<Void> removeMember(@AuthenticationPrincipal UUID requesterId,
                                          @PathVariable UUID id,
                                          @PathVariable UUID userId) {
        teamService.removeMember(requesterId, id, userId);
        return ApiResponse.ok(null, "Member removed");
    }

    @DeleteMapping("/api/teams/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UUID userId,
                                    @PathVariable UUID id) {
        teamService.delete(userId, id);
        return ApiResponse.ok(null, "Team deleted");
    }
}
