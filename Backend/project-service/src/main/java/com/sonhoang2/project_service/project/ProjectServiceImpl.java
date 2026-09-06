package com.sonhoang2.project_service.project;

import com.sonhoang2.project_service.common.exception.ResourceConflictException;
import com.sonhoang2.project_service.common.exception.ResourceNotFoundException;
import com.sonhoang2.project_service.events.EventPublisher;
import com.sonhoang2.project_service.events.ProjectInvitationCreatedEvent;
import com.sonhoang2.project_service.common.dto.PageResponse;
import com.sonhoang2.project_service.common.dto.UserResponse;
import com.sonhoang2.project_service.project.dto.ActiveSprint;
import com.sonhoang2.project_service.project.dto.CreateProjectRequest;
import com.sonhoang2.project_service.project.dto.InvitationDecision;
import com.sonhoang2.project_service.project.dto.InvitationDecisionRequest;
import com.sonhoang2.project_service.project.dto.InviteMemberRequest;
import com.sonhoang2.project_service.project.dto.ListProjectRequest;
import com.sonhoang2.project_service.project.dto.UpdateProjectRequest;
import com.sonhoang2.project_service.project.dto.MemberInfo;
import com.sonhoang2.project_service.project.dto.OwnerInfo;
import com.sonhoang2.project_service.project.dto.ProjectDetailResponse;
import com.sonhoang2.project_service.project.dto.ProjectInvitationResponse;
import com.sonhoang2.project_service.project.dto.ProjectMemberResponse;
import com.sonhoang2.project_service.project.dto.ProjectResponse;
import com.sonhoang2.project_service.project.dto.TaskStats;
import com.sonhoang2.project_service.project.entity.Project;
import com.sonhoang2.project_service.project.entity.ProjectInvitation;
import com.sonhoang2.project_service.project.entity.ProjectInvitationStatus;
import com.sonhoang2.project_service.project.entity.ProjectMember;
import com.sonhoang2.project_service.project.entity.ProjectMemberRole;
import com.sonhoang2.project_service.project.feign.TaskServiceClient;
import com.sonhoang2.project_service.project.feign.UserServiceClient;
import com.sonhoang2.common.ratelimit.RateLimit;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectInvitationRepository projectInvitationRepository;
    private final UserServiceClient userServiceClient;
    private final TaskServiceClient taskServiceClient;
    private final EventPublisher eventPublisher;

    private void assertUserExists(UUID userId) {
        try {
            userServiceClient.findById(userId);
        } catch (FeignException.NotFound e) {
            throw new ResourceNotFoundException("User with id " + userId + " not found");
        }
    }

    @Override
    @Transactional(readOnly = true)
    @RateLimit(points = 150, durationSeconds = 60, keyPrefix = "project:list")
    public PageResponse<ProjectDetailResponse> listAllProject(Pageable pageable,
                                                              UUID userId,
                                                              ListProjectRequest request) {
        Page<Project> projectPage;

        // Apply search filter if provided
        if (request.getSearch() != null && !request.getSearch().trim().isEmpty()) {
            projectPage = projectRepository.searchByNameOrDescription(request.getSearch(), pageable);
        } else {
            projectPage = projectRepository.findAll(pageable);
        }

        // Convert to response DTOs
        List<ProjectDetailResponse> content = projectPage.getContent()
                .stream()
                .map(project -> toProjectDetailResponse(project, userId))
                .collect(Collectors.toList());

        // Apply custom sorting if specified
        if (request.getSortBy() != null && !request.getSortBy().trim().isEmpty()) {
            Sort.Direction direction = request.getSortDirection() != null && request.getSortDirection()
                    .equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;

            content.sort((p1, p2) -> {
                int comparison;
                switch (request.getSortBy().toLowerCase()) {
                    case "membercount":
                        comparison = Integer.compare(p1.getMemberCount(), p2.getMemberCount());
                        break;
                    case "taskcount":
                        comparison = Integer.compare(p1.getTaskStats().getTotal(), p2.getTaskStats().getTotal());
                        break;
                    case "createdat":
                        comparison = p1.getCreatedAt().compareTo(p2.getCreatedAt());
                        break;
                    case "updatedat":
                        comparison = p1.getUpdatedAt().compareTo(p2.getUpdatedAt());
                        break;
                    default:
                        return 0;
                }
                return direction == Sort.Direction.ASC ? comparison : -comparison;
            });
        }

        return new PageResponse<>(content,
                projectPage.getNumber(),
                projectPage.getSize(),
                projectPage.getTotalElements(),
                projectPage.getTotalPages(),
                projectPage.hasNext(),
                projectPage.hasPrevious(),
                projectPage.getNumberOfElements());
    }

    @Override
    public ProjectResponse create(CreateProjectRequest request, UUID userId) {
        // Use the passed userId for the owner
        Project project = projectRepository.save(Project.builder()
                .name(request.getName())
                .description(request.getDescription())
                .ownerId(userId)
                .build());

        projectMemberRepository.save(ProjectMember.builder()
                .project(project)
                .userId(userId)
                .role(ProjectMemberRole.OWNER)
                .build());

        return toProjectResponse(project);
    }

    @Override
    @Transactional(readOnly = true)
    @RateLimit(points = 150, durationSeconds = 60, keyPrefix = "project:list")
    public ProjectResponse getProjectById(UUID id, UUID userId) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project with id " + id + " not found"));

        // Verify that the requesting user is a member
        if (!projectMemberRepository.existsByProjectIdAndUserId(id, userId)) {
            throw new AccessDeniedException("You are not a member of this project");
        }

        return toProjectResponse(project);
    }

    @Override
    @RateLimit(points = 15, durationSeconds = 60, keyPrefix = "project:invite")
    public ProjectInvitationResponse inviteMember(UUID projectId, InviteMemberRequest request, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project with id " + projectId + " not found"));

        // Check current user's membership and role using the passed userId
        ProjectMember currentMembership = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new AccessDeniedException("You are not a member of this project"));

        if (currentMembership.getRole() != ProjectMemberRole.OWNER && currentMembership.getRole() != ProjectMemberRole.ADMIN) {
            throw new AccessDeniedException("Only owner or admin can invite members");
        }

        UUID inviteeId = request.getUserId();
        assertUserExists(inviteeId);

        if (projectMemberRepository.existsByProjectIdAndUserId(projectId, inviteeId)) {
            throw new ResourceConflictException("User is already a project member");
        }

        if (projectInvitationRepository.existsByProjectIdAndInviteeIdAndStatus(projectId,
                inviteeId,
                ProjectInvitationStatus.PENDING)) {
            throw new ResourceConflictException("A pending invitation already exists for this user");
        }

        ProjectInvitation invitation = projectInvitationRepository.save(ProjectInvitation.builder()
                .project(project)
                .invitedById(userId)          // the one who invites (current user)
                .inviteeId(inviteeId)
                .status(ProjectInvitationStatus.PENDING)
                .build());

        // Emit event for notification
        ProjectInvitationCreatedEvent event = ProjectInvitationCreatedEvent.builder()
                .invitationId(invitation.getId())
                .projectId(project.getId())
                .projectName(project.getName())
                .invitedById(userId)
                .inviteeId(inviteeId)
                .eventType("PROJECT_INVITATION")
                .build();

        eventPublisher.publishProjectInvitationCreatedEvent(event);

        return toInvitationResponse(invitation);
    }

    @Override
    public ProjectInvitationResponse decideInvitation(UUID invitationId,
                                                      InvitationDecisionRequest request,
                                                      UUID userId) {
        ProjectInvitation invitation = findInvitationByIdOrThrow(invitationId);

        if (!invitation.getInviteeId().equals(userId)) {
            throw new AccessDeniedException("Only the invited user can respond to this invitation");
        }

        if (invitation.getStatus() != ProjectInvitationStatus.PENDING) {
            throw new ResourceConflictException("Invitation is no longer pending");
        }

        if (request.getDecision() == InvitationDecision.ACCEPT) {
            if (projectMemberRepository.existsByProjectIdAndUserId(invitation.getProject().getId(), userId)) {
                throw new ResourceConflictException("User is already a project member");
            }

            projectMemberRepository.save(ProjectMember.builder()
                    .project(invitation.getProject())
                    .userId(userId)
                    .role(ProjectMemberRole.MEMBER)
                    .build());

            invitation.setStatus(ProjectInvitationStatus.ACCEPTED);
        } else {
            invitation.setStatus(ProjectInvitationStatus.REJECTED);
        }

        invitation.setRespondedAt(Instant.now());

        projectInvitationRepository.save(invitation);

        return toInvitationResponse(invitation);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectMemberResponse> listMembers(UUID projectId, UUID userId) {
        // Verify that the requesting user is a member
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new AccessDeniedException("You are not a member of this project");
        }

        return projectMemberRepository.findByProjectIdOrderByJoinedAtAsc(projectId)
                .stream()
                .map(this::toMemberResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectInvitationResponse> listInvitations(UUID projectId, UUID userId) {
        // Verify that the requesting user is a member
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new AccessDeniedException("You are not a member of this project");
        }

        return projectInvitationRepository.findByProjectId(projectId).stream().map(this::toInvitationResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectInvitationResponse> listInvitationsByInvitee(UUID userId) {
        return projectInvitationRepository.findByInviteeId(userId).stream().map(this::toInvitationResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    @RateLimit(points = 150, durationSeconds = 60, keyPrefix = "project:list")
    public PageResponse<ProjectDetailResponse> getMyProjects(Pageable pageable,
                                                             UUID userId,
                                                             ListProjectRequest request) {
        // Get all project memberships for the current user
        List<ProjectMember> memberships = projectMemberRepository.findByUserId(userId);

        // Extract project IDs
        List<UUID> projectIds = memberships.stream()
                .map(member -> member.getProject().getId())
                .collect(Collectors.toList());

        // Fetch projects
        List<Project> projects = projectRepository.findAllById(projectIds);

        // Apply search filter if provided
        if (request.getSearch() != null && !request.getSearch().trim().isEmpty()) {
            String searchLower = request.getSearch().toLowerCase();
            projects = projects.stream()
                    .filter(p -> p.getName().toLowerCase().contains(searchLower) ||
                            (p.getDescription() != null && p.getDescription().toLowerCase().contains(searchLower)))
                    .toList();
        }

        // Convert to response DTOs
        List<ProjectDetailResponse> content = projects.stream()
                .map(project -> toProjectDetailResponse(project, userId))
                .collect(Collectors.toList());

        // Apply custom sorting if specified
        if (request.getSortBy() != null && !request.getSortBy().trim().isEmpty()) {
            Sort.Direction direction = request.getSortDirection() != null && request.getSortDirection()
                    .equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;

            content.sort((p1, p2) -> {
                int comparison;
                switch (request.getSortBy().toLowerCase()) {
                    case "membercount":
                        comparison = Integer.compare(p1.getMemberCount(), p2.getMemberCount());
                        break;
                    case "taskcount":
                        comparison = Integer.compare(p1.getTaskStats().getTotal(), p2.getTaskStats().getTotal());
                        break;
                    case "createdat":
                        comparison = p1.getCreatedAt().compareTo(p2.getCreatedAt());
                        break;
                    case "updatedat":
                        comparison = p1.getUpdatedAt().compareTo(p2.getUpdatedAt());
                        break;
                    default:
                        return 0;
                }
                return direction == Sort.Direction.ASC ? comparison : -comparison;
            });
        }

        // Apply pagination manually
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), content.size());

        if (start >= content.size()) {
            content = List.of();
        } else {
            content = content.subList(start, end);
        }

        return new PageResponse<>(content,
                pageable.getPageNumber(),
                pageable.getPageSize(),
                projects.size(),
                (int) Math.ceil((double) projects.size() / pageable.getPageSize()),
                end < projects.size(),
                start > 0,
                content.size());
    }

    @Override
    @Transactional(readOnly = true)
    @RateLimit(points = 150, durationSeconds = 60, keyPrefix = "project:list")
    public PageResponse<Map<String, Object>> getProjectTasks(UUID projectId, UUID userId, Pageable pageable) {
        // Verify that the requesting user is a member
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new AccessDeniedException("You are not a member of this project");
        }

        String sort = buildSortParam(pageable.getSort());

        var response = taskServiceClient.findByProjectId(
                projectId,
                userId,
                pageable.getPageNumber(),
                pageable.getPageSize(),
                sort
        );

        return response.data().get("page");
    }

    @Override
    public ProjectResponse update(UUID id, UpdateProjectRequest request, UUID userId) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project with id " + id + " not found"));

        // Verify that the requesting user is a member
        ProjectMember membership = projectMemberRepository.findByProjectIdAndUserId(id, userId)
                .orElseThrow(() -> new AccessDeniedException("You are not a member of this project"));

        // Verify that the requesting user is owner or admin
        if (membership.getRole() != ProjectMemberRole.OWNER && membership.getRole() != ProjectMemberRole.ADMIN) {
            throw new AccessDeniedException("Only owner or admin can update the project");
        }

        project.setName(request.getName());
        project.setDescription(request.getDescription());

        Project updatedProject = projectRepository.save(project);
        return toProjectResponse(updatedProject);
    }

    @Override
    public void delete(UUID id, UUID userId) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project with id " + id + " not found"));

        // Verify that the requesting user is a member
        ProjectMember membership = projectMemberRepository.findByProjectIdAndUserId(id, userId)
                .orElseThrow(() -> new AccessDeniedException("You are not a member of this project"));

        // Verify that the requesting user is owner or admin
        if (membership.getRole() != ProjectMemberRole.OWNER && membership.getRole() != ProjectMemberRole.ADMIN) {
            throw new AccessDeniedException("Only owner or admin can delete the project");
        }

        projectRepository.delete(project);
    }

    private String buildSortParam(Sort sort) {
        if (sort == null || sort.isUnsorted()) {
            return null;
        }
        return sort.stream()
                .map(order -> order.getProperty() + "," + order.getDirection().name().toLowerCase())
                .collect(Collectors.joining(","));
    }

    // Helper methods (unchanged)
    private ProjectInvitation findInvitationByIdOrThrow(UUID invitationId) {
        return projectInvitationRepository.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation with id " + invitationId + " not found"));
    }

    private ProjectResponse toProjectResponse(Project project) {
        return ProjectResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .description(project.getDescription())
                .ownerId(project.getOwnerId())
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }

    private ProjectInvitationResponse toInvitationResponse(ProjectInvitation invitation) {
        return ProjectInvitationResponse.builder()
                .id(invitation.getId())
                .projectId(invitation.getProject().getId())
                .invitedById(invitation.getInvitedById())
                .inviteeId(invitation.getInviteeId())
                .status(invitation.getStatus())
                .createdAt(invitation.getCreatedAt())
                .respondedAt(invitation.getRespondedAt())
                .build();
    }

    private ProjectMemberResponse toMemberResponse(ProjectMember member) {
        return ProjectMemberResponse.builder()
                .userId(member.getUserId())
                .role(member.getRole())
                .joinedAt(member.getJoinedAt())
                .build();
    }

    private ProjectDetailResponse toProjectDetailResponse(Project project, UUID userId) {
        // Get owner info
        OwnerInfo owner = getOwnerInfo(project.getOwnerId());

        // Get user's role in the project
        ProjectMemberRole myRole = projectMemberRepository.findByProjectIdAndUserId(project.getId(), userId)
                .map(ProjectMember::getRole)
                .orElse(null);

        // Get members
        List<ProjectMember> members = projectMemberRepository.findByProjectIdOrderByJoinedAtAsc(project.getId());
        int memberCount = members.size();

        // Get member info (limit to first 5 for preview)
        List<MemberInfo> memberInfos = members.stream()
                .limit(5)
                .map(member -> getMemberInfo(member.getUserId()))
                .collect(Collectors.toList());

        // Task stats - fetch from task service
        TaskStats taskStats = getTaskStats(project.getId());

        // Active sprint - placeholder (implement when sprint service is available)
        ActiveSprint activeSprint = null;

        return ProjectDetailResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .description(project.getDescription())
                .owner(owner)
                .myRole(myRole)
                .memberCount(memberCount)
                .members(memberInfos)
                .taskStats(taskStats)
                .activeSprint(activeSprint)
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }

    private OwnerInfo getOwnerInfo(UUID ownerId) {
        try {
            var response = userServiceClient.findById(ownerId);
            UserResponse user = response.data().get("user");
            return OwnerInfo.builder()
                    .id(user.getId())
                    .fullName(user.getFullName())
                    .avatarUrl(user.getAvatarUrl())
                    .build();
        } catch (FeignException e) {
            return OwnerInfo.builder().id(ownerId).fullName("Unknown").avatarUrl(null).build();
        }
    }

    private MemberInfo getMemberInfo(UUID userId) {
        try {
            var response = userServiceClient.findById(userId);
            UserResponse user = response.data().get("user");
            return MemberInfo.builder().id(user.getId()).avatarUrl(user.getAvatarUrl()).build();
        } catch (FeignException e) {
            return MemberInfo.builder().id(userId).avatarUrl(null).build();
        }
    }

    private TaskStats getTaskStats(UUID projectId) {
        try {
            var response = taskServiceClient.getTaskStatsByProjectId(projectId);
            @SuppressWarnings("unchecked")
            Map<String, Object> statsData = (Map<String, Object>) response.data().get("stats");

            int todo = (Integer) statsData.getOrDefault("todo", 0);
            int inProgress = (Integer) statsData.getOrDefault("inProgress", 0);
            int done = (Integer) statsData.getOrDefault("done", 0);

            return TaskStats.builder()
                    .total(todo + inProgress + done)
                    .todo(todo)
                    .inProgress(inProgress)
                    .done(done)
                    .build();
        } catch (FeignException | ClassCastException e) {
            return emptyTaskStats();
        }
    }

    private TaskStats emptyTaskStats() {
        return TaskStats.builder().total(0).todo(0).inProgress(0).done(0).build();
    }
}