package com.sonhoang2.task_service.task;

import com.sonhoang2.task_service.attachment.entity.TaskAttachment;
import com.sonhoang2.task_service.comment.entity.TaskComment;
import com.sonhoang2.task_service.common.dto.PageResponse;
import com.sonhoang2.task_service.common.exception.ResourceNotFoundException;
import com.sonhoang2.task_service.events.EventPublisher;
import com.sonhoang2.task_service.events.TaskAssignedEvent;
import com.sonhoang2.task_service.feign.ProjectServiceClient;
import com.sonhoang2.task_service.task.dto.TaskAttachmentResponse;
import com.sonhoang2.task_service.task.dto.TaskCommentResponse;
import com.sonhoang2.task_service.task.dto.TaskCreateRequest;
import com.sonhoang2.task_service.task.dto.TaskDetailResponse;
import com.sonhoang2.task_service.task.dto.TaskDistributionResponse;
import com.sonhoang2.task_service.task.dto.TaskLabelResponse;
import com.sonhoang2.task_service.task.dto.TaskResponse;
import com.sonhoang2.task_service.task.dto.TaskStats;
import com.sonhoang2.task_service.task.dto.TaskUpdateRequest;
import com.sonhoang2.task_service.task.entity.Task;
import com.sonhoang2.task_service.task.entity.TaskStatus;
import com.sonhoang2.task_service.tasklabel.entity.TaskLabel;
import com.sonhoang2.common.ratelimit.RateLimit;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class TaskServiceImpl implements TaskService {

    private final TaskRepository taskRepository;
    private final ProjectServiceClient projectServiceClient;
    private final ModelMapper modelMapper;
    private final EventPublisher eventPublisher;
    private final TaskLeakHolder leakHolder;

    private PageResponse<TaskResponse> toPageResponse(Page<Task> page) {
        return new PageResponse<>(page.getContent().stream().map(this::toResponse).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext(),
                page.hasPrevious(),
                page.getNumberOfElements());
    }

    @Override
    public TaskResponse create(TaskCreateRequest request, UUID userId) {
        System.out.print("Creating task with projectId: " + request.getProjectId() + "\n" + "userId " + userId);
        try {
            projectServiceClient.findById(request.getProjectId(), userId);
        } catch (FeignException.NotFound e) {
            throw new ResourceNotFoundException("Project not found: " + request.getProjectId());
        }

        Task task = modelMapper.map(request, Task.class);

        leakHolder.hold(task);
        return toResponse(taskRepository.save(task));
    }

    @Override
    @RateLimit(points = 150, durationSeconds = 60, keyPrefix = "task:list")
    public PageResponse<TaskResponse> findAll(String status, String keyword, Pageable pageable) {
        Page<Task> page;

        if (status != null && keyword != null) {
            page = taskRepository.findByStatusAndTitleContainingIgnoreCase(TaskStatus.valueOf(status),
                    keyword,
                    pageable);
        } else if (status != null) {
            page = taskRepository.findByStatus(TaskStatus.valueOf(status), pageable);
        } else if (keyword != null) {
            page = taskRepository.findByTitleContainingIgnoreCase(keyword, pageable);
        } else {
            page = taskRepository.findAll(pageable);
        }

        return toPageResponse(page);
    }

    @Override
    @Transactional(readOnly = true)
    @RateLimit(points = 150, durationSeconds = 60, keyPrefix = "task:list")
    public TaskResponse findById(UUID id) {
        return toResponse(findTaskByIdOrThrow(id));
    }

    @Override
    public TaskResponse update(UUID id, TaskUpdateRequest request) {
        Task task = findTaskByIdOrThrow(id);
        UUID oldAssigneeId = task.getAssigneeId();
        modelMapper.map(request, task);


        // Publish TaskAssignedEvent if assigneeId changed
        if (request.getAssigneeId() != null && !request.getAssigneeId().equals(oldAssigneeId)) {
            TaskAssignedEvent event = TaskAssignedEvent.builder()
                    .taskId(task.getId())
                    .projectId(task.getProjectId())
                    .taskTitle(task.getTitle())
                    .assigneeId(task.getAssigneeId())
                    .reporterId(task.getReporterId())
                    .eventType("TASK_ASSIGNED")
                    .build();
            eventPublisher.publishTaskAssignedEvent(event);
        }

        return toResponse(task);
    }

    @Override
    public void delete(UUID id) {
        taskRepository.delete(findTaskByIdOrThrow(id));
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "taskDistribution", key = "'status_count'")
    public TaskDistributionResponse getTaskDistribution() {
        List<Object[]> rows = taskRepository.countByStatusGrouped();
        Map<TaskStatus, Long> counts = rows.stream()
                .collect(Collectors.toMap(
                        row -> (TaskStatus) row[0],
                        row -> (Long) row[1]
                ));

        return TaskDistributionResponse.builder()
                .todo(counts.getOrDefault(TaskStatus.TODO, 0L).intValue())
                .inProgress(counts.getOrDefault(TaskStatus.IN_PROGRESS, 0L).intValue())
                .done(counts.getOrDefault(TaskStatus.DONE, 0L).intValue())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    @RateLimit(points = 150, durationSeconds = 60, keyPrefix = "task:list")
    public PageResponse<TaskDetailResponse> findByProjectId(UUID projectId, Pageable pageable) {
        Page<Task> taskPage = taskRepository.findByProjectId(projectId, pageable);
        List<Task> tasks = taskPage.getContent();

        if (tasks.isEmpty()) {
            return new PageResponse<>(List.of(),
                    taskPage.getNumber(),
                    taskPage.getSize(),
                    taskPage.getTotalElements(),
                    taskPage.getTotalPages(),
                    taskPage.hasNext(),
                    taskPage.hasPrevious(),
                    taskPage.getNumberOfElements());
        }

        List<UUID> taskIds = tasks.stream().map(Task::getId).collect(Collectors.toList());

        List<Task> tasksWithComments = taskRepository.findByProjectIdWithComments(projectId);
        Map<UUID, Task> taskMap = tasksWithComments.stream()
                .filter(t -> taskIds.contains(t.getId()))
                .collect(Collectors.toMap(Task::getId, t -> t));

        List<Task> tasksWithAttachments = taskRepository.findByProjectIdWithAttachments(projectId);
        tasksWithAttachments.forEach(t -> {
            if (taskIds.contains(t.getId())) {
                Task task = taskMap.get(t.getId());
                if (task != null) {
                    task.setAttachments(t.getAttachments());
                }
            }
        });

        List<Task> tasksWithLabels = taskRepository.findByProjectIdWithLabels(projectId);
        tasksWithLabels.forEach(t -> {
            if (taskIds.contains(t.getId())) {
                Task task = taskMap.get(t.getId());
                if (task != null) {
                    task.setTaskLabels(t.getTaskLabels());
                }
            }
        });

        List<TaskDetailResponse> responses = taskIds.stream()
                .map(taskMap::get)
                .map(this::toDetailResponse)
                .collect(Collectors.toList());

        return new PageResponse<>(responses,
                taskPage.getNumber(),
                taskPage.getSize(),
                taskPage.getTotalElements(),
                taskPage.getTotalPages(),
                taskPage.hasNext(),
                taskPage.hasPrevious(),
                taskPage.getNumberOfElements());
    }

    @Override
    @Transactional(readOnly = true)
    public TaskStats getTaskStatsByProjectId(UUID projectId) {
        List<Object[]> rows = taskRepository.countByStatusGroupedByProjectId(projectId);
        Map<TaskStatus, Long> counts = rows.stream()
                .collect(Collectors.toMap(
                        row -> (TaskStatus) row[0],
                        row -> (Long) row[1]
                ));

        int todo = counts.getOrDefault(TaskStatus.TODO, 0L).intValue();
        int inProgress = counts.getOrDefault(TaskStatus.IN_PROGRESS, 0L).intValue();
        int done = counts.getOrDefault(TaskStatus.DONE, 0L).intValue();

        return TaskStats.builder()
                .total(todo + inProgress + done)
                .todo(todo)
                .inProgress(inProgress)
                .done(done)
                .build();
    }

    private Task findTaskByIdOrThrow(UUID id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Task with id " + id + " not found"));
    }

    private TaskResponse toResponse(Task task) {
        return TaskResponse.builder()
                .id(task.getId())
                .projectId(task.getProjectId())
                .title(task.getTitle())
                .description(task.getDescription())
                .status(task.getStatus())
                .priority(task.getPriority())
                .assigneeId(task.getAssigneeId())
                .reporterId(task.getReporterId())
                .dueDate(task.getDueDate())
                .startDate(task.getStartDate())
                .parentTaskId(task.getParentTaskId())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .build();
    }

    private TaskDetailResponse toDetailResponse(Task task) {
        List<TaskCommentResponse> commentResponses = task.getComments().stream()
                .map(comment -> TaskCommentResponse.builder()
                        .id(comment.getId())
                        .taskId(comment.getTaskId())
                        .userId(comment.getUserId())
                        .content(comment.getContent())
                        .createdAt(comment.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        List<TaskAttachmentResponse> attachmentResponses = task.getAttachments().stream()
                .map(attachment -> TaskAttachmentResponse.builder()
                        .id(attachment.getId())
                        .taskId(attachment.getTaskId())
                        .fileUrl(attachment.getFileUrl())
                        .fileName(attachment.getFileName())
                        .uploadedBy(attachment.getUploadedBy())
                        .createdAt(attachment.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        List<TaskLabelResponse> labelResponses = task.getTaskLabels().stream()
                .map(taskLabel -> TaskLabelResponse.builder()
                        .taskId(taskLabel.getTaskId())
                        .labelId(taskLabel.getLabelId())
                        .build())
                .collect(Collectors.toList());

        return TaskDetailResponse.builder()
                .id(task.getId())
                .projectId(task.getProjectId())
                .title(task.getTitle())
                .description(task.getDescription())
                .status(task.getStatus())
                .priority(task.getPriority())
                .assigneeId(task.getAssigneeId())
                .reporterId(task.getReporterId())
                .dueDate(task.getDueDate())
                .startDate(task.getStartDate())
                .parentTaskId(task.getParentTaskId())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .comments(commentResponses)
                .attachments(attachmentResponses)
                .labels(labelResponses)
                .build();
    }
}