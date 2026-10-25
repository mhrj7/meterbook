package com.meterbook.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.meterbook.identity.audit.AuditAction;
import com.meterbook.identity.audit.Audited;
import com.meterbook.identity.support.TestDatabase;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * The code serves exactly the contract (engineering-rules.md: contracts first), and every endpoint
 * that changes something, or reads one resource by ID, is in the audit log (ADR-0013, TC-PLT-011).
 * The list comes from Spring's own handler mappings, so a new endpoint cannot be missed.
 */
@SpringBootTest
@Import(TestDatabase.class)
class ApiContractTest {

  private static final Path CONTRACT = Path.of("../../contracts/openapi/identity-v1.yaml");
  private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");

  /** Spring Boot's error page, not part of the API. */
  private static final Set<String> NOT_API = Set.of("/error");

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  /**
   * One operation: method and path with its variables unnamed, e.g. {@code GET /v1/projects/{}}.
   */
  private static String operation(String method, String path) {
    return method.toUpperCase(java.util.Locale.ROOT) + " " + path.replaceAll("\\{[^}]+}", "{}");
  }

  private record Endpoint(
      String operation, RequestMethod method, String path, HandlerMethod handler) {}

  private List<Endpoint> endpoints() {
    List<Endpoint> out = new ArrayList<>();
    for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
      for (String path : e.getKey().getPatternValues()) {
        if (NOT_API.contains(path)) {
          continue;
        }
        for (RequestMethod method : e.getKey().getMethodsCondition().getMethods()) {
          out.add(new Endpoint(operation(method.name(), path), method, path, e.getValue()));
        }
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Set<String> contractOperations() throws IOException {
    Map<String, Object> contract;
    try (InputStream in = Files.newInputStream(CONTRACT)) {
      contract = new Yaml().load(in);
    }
    Set<String> operations = new TreeSet<>();
    ((Map<String, Map<String, Object>>) contract.get("paths"))
        .forEach(
            (path, item) ->
                item.keySet().stream()
                    .filter(METHODS::contains)
                    .forEach(method -> operations.add(operation(method, path))));
    return operations;
  }

  @Test
  void the_service_serves_exactly_the_operations_of_its_contract() throws IOException {
    Set<String> served = new TreeSet<>();
    endpoints().forEach(e -> served.add(e.operation()));

    assertThat(served).isEqualTo(contractOperations());
  }

  @Test
  void every_change_names_its_audit_action() {
    for (Endpoint e : endpoints()) {
      if (e.method() == RequestMethod.GET) {
        continue;
      }
      Audited audited = e.handler().getMethodAnnotation(Audited.class);
      assertThat(audited).as(e.operation() + " must be @Audited").isNotNull();
      assertThat(audited.value().isChange()).as(e.operation() + " records a change").isTrue();
    }
  }

  @Test
  void every_read_by_id_is_audited_when_refused() {
    for (Endpoint e : endpoints()) {
      if (e.method() != RequestMethod.GET || !e.path().contains("{")) {
        continue;
      }
      Audited audited = e.handler().getMethodAnnotation(Audited.class);
      assertThat(audited).as(e.operation() + " must be @Audited").isNotNull();
      assertThat(audited.value().isChange()).as(e.operation() + " is a read").isFalse();
    }
  }

  @Test
  void each_change_belongs_to_one_endpoint_and_names_a_real_path_variable() {
    Set<AuditAction> seen = new TreeSet<>();
    for (Endpoint e : endpoints()) {
      Audited audited = e.handler().getMethodAnnotation(Audited.class);
      if (audited == null) {
        continue;
      }
      if (audited.value().isChange()) {
        assertThat(seen.add(audited.value()))
            .as(audited.value() + " is used by one endpoint only")
            .isTrue();
      }
      if (!audited.targetVariable().isEmpty()) {
        assertThat(e.path())
            .as(e.operation() + " has the variable its audit target names")
            .contains("{" + audited.targetVariable() + "}");
      }
    }
  }
}
