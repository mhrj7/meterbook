package com.meterbook.identity.scim;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** {@code /scim/v2/Groups} (RFC 7644 §3), for the tenant whose SCIM token made the request. */
@RestController
@RequestMapping(
    path = "/scim/v2/Groups",
    produces = {ScimErrors.MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
public class ScimGroupsController {

  private static final MediaType SCIM = MediaType.parseMediaType(ScimErrors.MEDIA_TYPE);

  private final ScimGroups groups;

  public ScimGroupsController(ScimGroups groups) {
    this.groups = groups;
  }

  @PostMapping
  ResponseEntity<ObjectNode> create(ScimAuthentication auth, @RequestBody JsonNode body) {
    ScimGroups.Group group = groups.create(auth.tenantId(), body);
    return ResponseEntity.created(URI.create("/scim/v2/Groups/" + group.id()))
        .contentType(SCIM)
        .header(HttpHeaders.ETAG, group.etag())
        .body(groups.toJson(auth.tenantId(), group));
  }

  @GetMapping("/{id}")
  ResponseEntity<ObjectNode> get(ScimAuthentication auth, @PathVariable UUID id) {
    ScimGroups.Group group = groups.get(auth.tenantId(), id);
    return ResponseEntity.ok()
        .contentType(SCIM)
        .header(HttpHeaders.ETAG, group.etag())
        .body(groups.toJson(auth.tenantId(), group));
  }

  @GetMapping
  ResponseEntity<Map<String, Object>> list(
      ScimAuthentication auth,
      @RequestParam(name = "filter", required = false) String filter,
      @RequestParam(name = "startIndex", defaultValue = "1") int startIndex,
      @RequestParam(name = "count", defaultValue = "100") int count) {
    int start = Math.max(1, startIndex);
    int size = Math.clamp(count, 0, ScimDiscoveryController.MAX_RESULTS);
    var page =
        groups.list(auth.tenantId(), ScimFilter.parse(filter, ScimGroups.FILTERABLE), start, size);
    List<ObjectNode> resources =
        page.getValue().stream().map(g -> groups.toJson(auth.tenantId(), g)).toList();
    return ResponseEntity.ok()
        .contentType(SCIM)
        .body(
            Map.of(
                "schemas", List.of(ScimDiscoveryController.LIST_SCHEMA),
                "totalResults", page.getKey(),
                "startIndex", start,
                "itemsPerPage", resources.size(),
                "Resources", resources));
  }

  @PatchMapping("/{id}")
  ResponseEntity<ObjectNode> patch(
      ScimAuthentication auth,
      @PathVariable UUID id,
      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
      @RequestBody JsonNode body) {
    ScimGroups.Group group = groups.patch(auth.tenantId(), id, ScimPatch.operations(body), ifMatch);
    return ResponseEntity.ok()
        .contentType(SCIM)
        .header(HttpHeaders.ETAG, group.etag())
        .body(groups.toJson(auth.tenantId(), group));
  }

  @DeleteMapping("/{id}")
  ResponseEntity<Void> delete(ScimAuthentication auth, @PathVariable UUID id) {
    groups.delete(auth.tenantId(), id);
    return ResponseEntity.noContent().build();
  }
}
