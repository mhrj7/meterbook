package com.meterbook.identity.projects;

import com.meterbook.identity.audit.AuditAction;
import com.meterbook.identity.audit.Audited;
import com.meterbook.identity.idempotency.Idempotency;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.web.ApiException;
import com.meterbook.identity.web.Pages;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/projects} and their environments (contracts/openapi/identity-v1.yaml). */
@RestController
public class ProjectController {

  private static final String SLUG = "^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$";

  /** Request body of {@code POST /v1/projects}. */
  public record CreateProject(
      @NotBlank @Size(max = 200) String name, @NotNull @Pattern(regexp = SLUG) String slug) {}

  /** Request body of {@code PATCH /v1/projects/{id}}. */
  public record UpdateProject(@NotBlank @Size(max = 200) String name) {}

  /** Request body of {@code POST /v1/projects/{id}/environments}. */
  public record CreateEnvironment(
      @NotBlank @Size(max = 200) String name,
      @NotNull @Pattern(regexp = SLUG) String slug,
      @NotNull @Pattern(regexp = "live|test") String kind) {}

  /** A page of projects. */
  public record ProjectList(List<Projects.Project> data, boolean hasMore, String nextCursor) {
    public ProjectList {
      data = List.copyOf(data);
    }
  }

  /** A page of environments. */
  public record EnvironmentList(
      List<Projects.Environment> data, boolean hasMore, String nextCursor) {
    public EnvironmentList {
      data = List.copyOf(data);
    }
  }

  private final Projects projects;
  private final Idempotency idempotency;

  public ProjectController(Projects projects, Idempotency idempotency) {
    this.projects = projects;
    this.idempotency = idempotency;
  }

  @PostMapping("/v1/projects")
  @Audited(value = AuditAction.PROJECT_CREATED, targetVariable = "")
  public ResponseEntity<?> create(
      CallerAuthentication auth,
      @RequestHeader(name = Idempotency.HEADER, required = false) String key,
      @Valid @RequestBody CreateProject body) {
    return idempotency.execute(
        auth.getName(),
        key,
        "POST /v1/projects",
        body,
        () -> {
          var project = projects.create(auth.caller(), body.name(), body.slug());
          return ResponseEntity.created(URI.create("/v1/projects/" + project.id())).body(project);
        });
  }

  @GetMapping("/v1/projects")
  public ProjectList list(
      CallerAuthentication auth,
      @RequestParam(name = "limit", defaultValue = "50") int limit,
      @RequestParam(name = "starting_after", required = false) String startingAfter) {
    Pages.Page<Projects.Project> page =
        projects.list(auth.caller(), cursor(startingAfter), checked(limit));
    return new ProjectList(page.data(), page.hasMore(), page.nextCursor());
  }

  @GetMapping("/v1/projects/{id}")
  @Audited(AuditAction.PROJECT_READ)
  public Projects.Project get(CallerAuthentication auth, @PathVariable UUID id) {
    return projects.get(auth.caller(), id);
  }

  @PatchMapping("/v1/projects/{id}")
  @Audited(AuditAction.PROJECT_UPDATED)
  public Projects.Project rename(
      CallerAuthentication auth, @PathVariable UUID id, @Valid @RequestBody UpdateProject body) {
    return projects.rename(auth.caller(), id, body.name());
  }

  @PostMapping("/v1/projects/{id}/environments")
  @Audited(value = AuditAction.ENVIRONMENT_CREATED, targetVariable = "id", targetType = "project")
  public ResponseEntity<?> createEnvironment(
      CallerAuthentication auth,
      @PathVariable UUID id,
      @RequestHeader(name = Idempotency.HEADER, required = false) String key,
      @Valid @RequestBody CreateEnvironment body) {
    return idempotency.execute(
        auth.getName(),
        key,
        "POST /v1/projects/" + id + "/environments",
        body,
        () -> {
          var environment =
              projects.createEnvironment(auth.caller(), id, body.name(), body.slug(), body.kind());
          return ResponseEntity.created(
                  URI.create("/v1/projects/" + id + "/environments/" + environment.id()))
              .body(environment);
        });
  }

  @GetMapping("/v1/projects/{id}/environments")
  @Audited(value = AuditAction.ENVIRONMENT_READ, targetVariable = "id", targetType = "project")
  public EnvironmentList listEnvironments(
      CallerAuthentication auth,
      @PathVariable UUID id,
      @RequestParam(name = "limit", defaultValue = "50") int limit,
      @RequestParam(name = "starting_after", required = false) String startingAfter) {
    Pages.Page<Projects.Environment> page =
        projects.listEnvironments(auth.caller(), id, cursor(startingAfter), checked(limit));
    return new EnvironmentList(page.data(), page.hasMore(), page.nextCursor());
  }

  @GetMapping("/v1/projects/{id}/environments/{environmentId}")
  @Audited(value = AuditAction.ENVIRONMENT_READ, targetVariable = "environmentId")
  public Projects.Environment getEnvironment(
      CallerAuthentication auth, @PathVariable UUID id, @PathVariable UUID environmentId) {
    return projects.getEnvironment(auth.caller(), id, environmentId);
  }

  private static int checked(int limit) {
    if (limit < 1 || limit > 200) {
      throw ApiException.invalid("limit_out_of_range", "limit must be between 1 and 200", "limit");
    }
    return limit;
  }

  private static Optional<UUID> cursor(String startingAfter) {
    try {
      return Optional.ofNullable(startingAfter).map(UUID::fromString);
    } catch (IllegalArgumentException e) {
      throw ApiException.invalid(
          "cursor_invalid", "starting_after is not a cursor from this API", "starting_after");
    }
  }
}
