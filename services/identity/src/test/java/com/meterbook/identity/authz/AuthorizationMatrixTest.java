package com.meterbook.identity.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.meterbook.identity.db.Tables;
import com.meterbook.identity.security.CallerAuthentication;
import com.meterbook.identity.security.TestCallers;
import com.meterbook.identity.support.FakeDirectory;
import com.meterbook.identity.support.TenantMembers;
import com.meterbook.identity.support.TestDatabase;
import com.meterbook.identity.tenancy.TenantScope;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole matrix over HTTP (ADR-0014): every role calls every endpoint that needs a tenant
 * permission, and is refused (403 {@code permission_required}) exactly when its role lacks it. A
 * caller with no binding is refused everywhere. Endpoints come from Spring's handler mappings, so a
 * new one is in the matrix the moment it exists.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabase.class, AuthorizationMatrixTest.Fixture.class})
class AuthorizationMatrixTest {

  @TestConfiguration
  static class Fixture {
    @Bean
    @Primary
    FakeDirectory fakeDirectory() {
      return new FakeDirectory();
    }
  }

  @Autowired MockMvc mvc;
  @Autowired DSLContext db;
  @Autowired TenantScope scope;
  @Autowired JsonMapper json;

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  private record Endpoint(HttpMethod method, String path, Permission permission) {}

  private List<Endpoint> tenantEndpoints() {
    List<Endpoint> out = new ArrayList<>();
    for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
      Requires requires = e.getValue().getMethodAnnotation(Requires.class);
      if (requires == null || requires.value().isPlatform()) {
        continue;
      }
      for (String path : e.getKey().getPatternValues()) {
        for (var method : e.getKey().getMethodsCondition().getMethods()) {
          out.add(
              new Endpoint(
                  HttpMethod.valueOf(method.name()),
                  path.replaceAll("\\{[^}]+}", UUID.randomUUID().toString()),
                  requires.value()));
        }
      }
    }
    return out;
  }

  private UUID newTenant() {
    UUID id = UUID.randomUUID();
    scope.inTenant(
        id,
        () ->
            db.insertInto(Tables.TENANTS)
                .set(Tables.TENANTS.ID, id)
                .set(Tables.TENANTS.SLUG, "t-" + id.toString().substring(0, 8))
                .set(Tables.TENANTS.NAME, "Tenant")
                .set(Tables.TENANTS.CREATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .execute());
    return id;
  }

  private MvcResult call(CallerAuthentication caller, Endpoint endpoint) throws Exception {
    var builder =
        request(endpoint.method(), endpoint.path())
            .with(authentication(caller))
            .header("Idempotency-Key", UUID.randomUUID().toString());
    if (!endpoint.method().equals(HttpMethod.GET) && !endpoint.method().equals(HttpMethod.DELETE)) {
      builder.contentType("application/json").content("{}");
    }
    return mvc.perform(builder).andReturn();
  }

  private boolean refused(MvcResult result) throws Exception {
    if (result.getResponse().getStatus() != 403) {
      return false;
    }
    return json.readTree(result.getResponse().getContentAsString())
        .get("error")
        .get("code")
        .asString()
        .equals("permission_required");
  }

  @Test
  void every_role_is_refused_exactly_what_it_lacks() throws Exception {
    UUID tenant = newTenant();
    List<Endpoint> endpoints = tenantEndpoints();
    assertThat(endpoints).hasSizeGreaterThan(20);
    for (TenantRole role : TenantRole.values()) {
      CallerAuthentication caller = TenantMembers.with(db, scope, tenant, role);
      for (Endpoint endpoint : endpoints) {
        boolean allowed = role.permissions().contains(endpoint.permission());
        assertThat(refused(call(caller, endpoint)))
            .as("%s %s %s as %s", endpoint.method(), endpoint.path(), endpoint.permission(), role)
            .isEqualTo(!allowed);
      }
    }
  }

  @Test
  void a_member_without_a_binding_is_refused_everywhere() throws Exception {
    CallerAuthentication nobody = TestCallers.tenantUser(UUID.randomUUID(), newTenant());
    for (Endpoint endpoint : tenantEndpoints()) {
      assertThat(refused(call(nobody, endpoint)))
          .as("%s %s", endpoint.method(), endpoint.path())
          .isTrue();
    }
  }

  @Test
  void the_old_realm_role_in_the_token_grants_nothing() throws Exception {
    CallerAuthentication legacy =
        TestCallers.tenantUser(
            UUID.randomUUID(), newTenant(), com.meterbook.identity.security.Role.TENANT_ADMIN);
    for (Endpoint endpoint : tenantEndpoints()) {
      assertThat(refused(call(legacy, endpoint)))
          .as("%s %s", endpoint.method(), endpoint.path())
          .isTrue();
    }
  }
}
