package com.meterbook.identity.scim;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What this SCIM service provider supports (RFC 7644 §4). */
@RestController
@RequestMapping(
    path = "/scim/v2",
    produces = {ScimErrors.MEDIA_TYPE, "application/json"})
public class ScimDiscoveryController {

  static final String LIST_SCHEMA = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
  static final String USER_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
  static final String GROUP_SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Group";
  static final int MAX_RESULTS = 200;

  @GetMapping("/ServiceProviderConfig")
  Map<String, Object> serviceProviderConfig() {
    return Map.of(
        "schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"),
        "documentationUri", "https://github.com/mhrj7/meterbook/blob/main/docs/design/identity.md",
        "patch", Map.of("supported", true),
        "bulk", Map.of("supported", false, "maxOperations", 0, "maxPayloadSize", 0),
        "filter", Map.of("supported", true, "maxResults", MAX_RESULTS),
        "changePassword", Map.of("supported", false),
        "sort", Map.of("supported", false),
        "etag", Map.of("supported", true),
        "authenticationSchemes",
            List.of(
                Map.of(
                    "type", "oauthbearertoken",
                    "name", "SCIM token",
                    "description", "Tenant SCIM token issued by POST /v1/scim-tokens",
                    "primary", true)),
        "meta",
            Map.of(
                "resourceType",
                "ServiceProviderConfig",
                "location",
                "/scim/v2/ServiceProviderConfig"));
  }

  @GetMapping("/ResourceTypes")
  Map<String, Object> resourceTypes() {
    List<Map<String, Object>> types =
        List.of(
            resourceType("User", "/Users", USER_SCHEMA),
            resourceType("Group", "/Groups", GROUP_SCHEMA));
    return list(types);
  }

  @GetMapping("/Schemas")
  Map<String, Object> schemas() {
    return list(
        List.of(
            Map.of(
                "schemas",
                List.of("urn:ietf:params:scim:schemas:core:2.0:Schema"),
                "id",
                USER_SCHEMA,
                "name",
                "User"),
            Map.of(
                "schemas",
                List.of("urn:ietf:params:scim:schemas:core:2.0:Schema"),
                "id",
                GROUP_SCHEMA,
                "name",
                "Group")));
  }

  private static Map<String, Object> resourceType(String name, String endpoint, String schema) {
    return Map.of(
        "schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:ResourceType"),
        "id", name,
        "name", name,
        "endpoint", endpoint,
        "schema", schema,
        "meta",
            Map.of("resourceType", "ResourceType", "location", "/scim/v2/ResourceTypes/" + name));
  }

  static Map<String, Object> list(List<?> resources) {
    return Map.of(
        "schemas", List.of(LIST_SCHEMA),
        "totalResults", resources.size(),
        "startIndex", 1,
        "itemsPerPage", resources.size(),
        "Resources", resources);
  }
}
