package com.meterbook.identity.tenants;

import com.meterbook.identity.idempotency.Idempotency;
import com.meterbook.identity.security.CallerAuthentication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** {@code /v1/tenants} (contracts/openapi/identity-v1.yaml). */
@RestController
public class TenantController {

  /** Request body of {@code POST /v1/tenants}. */
  public record CreateTenant(
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Pattern(regexp = "^[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?$") String slug,
      @NotEmpty @Size(max = 20)
          List<
                  @NotBlank @Size(max = 253)
                  @Pattern(
                      regexp =
                          "^(?=.{1,253}$)([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$")
                  String>
              domains,
      @NotBlank @Email @Size(max = 254) String initialAdminEmail) {}

  /** Response body. */
  public record Tenant(
      UUID id, String name, String slug, List<String> domains, OffsetDateTime createdAt) {
    static Tenant of(TenantRepository.Tenant t) {
      return new Tenant(t.id(), t.name(), t.slug(), t.domains(), t.createdAt());
    }
  }

  private final TenantService tenants;
  private final Idempotency idempotency;

  public TenantController(TenantService tenants, Idempotency idempotency) {
    this.tenants = tenants;
    this.idempotency = idempotency;
  }

  @PostMapping("/v1/tenants")
  public ResponseEntity<?> create(
      CallerAuthentication auth,
      @RequestHeader(name = Idempotency.HEADER, required = false) String key,
      @Valid @RequestBody CreateTenant body) {
    return idempotency.execute(
        auth.getName(),
        key,
        "POST /v1/tenants",
        body,
        () -> {
          TenantRepository.Tenant tenant =
              tenants.create(
                  auth.caller(),
                  new TenantService.NewTenant(
                      body.name(), body.slug(), body.domains(), body.initialAdminEmail()));
          return ResponseEntity.created(URI.create("/v1/tenants/" + tenant.id()))
              .body(Tenant.of(tenant));
        });
  }

  @GetMapping("/v1/tenants/{id}")
  public Tenant get(CallerAuthentication auth, @PathVariable UUID id) {
    return Tenant.of(tenants.get(auth.caller(), id));
  }
}
