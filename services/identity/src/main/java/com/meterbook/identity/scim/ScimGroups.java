package com.meterbook.identity.scim;

import static com.meterbook.identity.db.Tables.SCIM_GROUPS;
import static com.meterbook.identity.db.Tables.SCIM_GROUP_MEMBERS;
import static com.meterbook.identity.db.Tables.SCIM_USERS;

import com.meterbook.identity.db.tables.records.ScimGroupsRecord;
import com.meterbook.identity.tenancy.TenantScope;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * SCIM groups (RFC 7643 §4.2). Stored for now; mapping groups to roles is MB-T11. Members must be
 * SCIM users of the same tenant.
 */
@Service
public class ScimGroups {

  static final Set<String> FILTERABLE = Set.of("displayName", "externalId");
  private static final Pattern MEMBER_PATH =
      Pattern.compile(
          "^members\\[\\s*value\\s+eq\\s+\"([^\"]+)\"\\s*\\]$", Pattern.CASE_INSENSITIVE);

  /** A stored group. */
  record Group(
      UUID id,
      String displayName,
      String externalId,
      long version,
      OffsetDateTime created,
      OffsetDateTime lastModified) {
    String etag() {
      return "W/\"" + version + "\"";
    }
  }

  private final DSLContext db;
  private final TenantScope scope;
  private final Clock clock;

  ScimGroups(DSLContext db, TenantScope scope, Clock clock) {
    this.db = db;
    this.scope = scope;
    this.clock = clock;
  }

  Group create(UUID tenant, JsonNode body) {
    return scope.inTenant(tenant, () -> createInScope(tenant, body));
  }

  private Group createInScope(UUID tenant, JsonNode body) {
    if (body == null || !body.isObject()) {
      throw ScimException.invalidSyntax("the body must be a SCIM Group object");
    }
    String displayName = ScimUser.text(body, "displayName");
    if (displayName == null || displayName.isBlank()) {
      throw ScimException.invalidValue("displayName is required");
    }
    requireUnique(tenant, displayName, null);
    OffsetDateTime now = OffsetDateTime.now(clock);
    Group group =
        new Group(
            UUID.randomUUID(), displayName.trim(), ScimUser.text(body, "externalId"), 1, now, now);
    db.insertInto(SCIM_GROUPS)
        .set(SCIM_GROUPS.ID, group.id())
        .set(SCIM_GROUPS.TENANT_ID, tenant)
        .set(SCIM_GROUPS.DISPLAY_NAME, group.displayName())
        .set(SCIM_GROUPS.DISPLAY_NAME_KEY, group.displayName().toLowerCase(Locale.ROOT))
        .set(SCIM_GROUPS.EXTERNAL_ID, group.externalId())
        .set(SCIM_GROUPS.VERSION, group.version())
        .set(SCIM_GROUPS.CREATED_AT, now)
        .set(SCIM_GROUPS.UPDATED_AT, now)
        .execute();
    addMembers(tenant, group.id(), memberIds(ScimUser.field(body, "members")));
    return group;
  }

  Group get(UUID tenant, UUID id) {
    return scope.inTenant(tenant, () -> getInScope(tenant, id));
  }

  private Group getInScope(UUID tenant, UUID id) {
    return db.selectFrom(SCIM_GROUPS)
        .where(SCIM_GROUPS.TENANT_ID.eq(tenant))
        .and(SCIM_GROUPS.ID.eq(id))
        .fetchOptional()
        .map(ScimGroups::map)
        .orElseThrow(() -> ScimException.notFound("no such group"));
  }

  Map.Entry<Integer, List<Group>> list(
      UUID tenant, Optional<ScimFilter> filter, int startIndex, int count) {
    return scope.inTenant(tenant, () -> listInScope(tenant, filter, startIndex, count));
  }

  private Map.Entry<Integer, List<Group>> listInScope(
      UUID tenant, Optional<ScimFilter> filter, int startIndex, int count) {
    Condition condition = SCIM_GROUPS.TENANT_ID.eq(tenant);
    if (filter.isPresent()) {
      condition =
          condition.and(
              filter.get().attribute().equals("displayName")
                  ? SCIM_GROUPS.DISPLAY_NAME_KEY.eq(filter.get().value().toLowerCase(Locale.ROOT))
                  : SCIM_GROUPS.EXTERNAL_ID.eq(filter.get().value()));
    }
    int total = db.fetchCount(SCIM_GROUPS, condition);
    List<Group> page =
        db.selectFrom(SCIM_GROUPS)
            .where(condition)
            .orderBy(SCIM_GROUPS.CREATED_AT, SCIM_GROUPS.ID)
            .offset(startIndex - 1)
            .limit(count)
            .fetch(ScimGroups::map);
    return new AbstractMap.SimpleImmutableEntry<>(total, page);
  }

  Group patch(UUID tenant, UUID id, List<ScimPatch.Operation> operations, String ifMatch) {
    return scope.inTenant(tenant, () -> patchInScope(tenant, id, operations, ifMatch));
  }

  private Group patchInScope(
      UUID tenant, UUID id, List<ScimPatch.Operation> operations, String ifMatch) {
    Group current = getInScope(tenant, id);
    if (ifMatch != null && !ifMatch.equals(current.etag()) && !ifMatch.equals("*")) {
      throw ScimException.preconditionFailed();
    }
    String displayName = current.displayName();
    String externalId = current.externalId();
    for (ScimPatch.Operation op : operations) {
      String path = op.path() == null ? null : op.path().trim();
      if (path == null) {
        if (!op.value().isObject()) {
          throw ScimException.invalidValue("an operation without a path needs an object value");
        }
        if (ScimUser.field(op.value(), "displayName") != null) {
          displayName = ScimUser.text(op.value(), "displayName");
        }
        if (ScimUser.field(op.value(), "externalId") != null) {
          externalId = ScimUser.text(op.value(), "externalId");
        }
        if (ScimUser.field(op.value(), "members") != null) {
          applyMembers(tenant, id, op.op(), memberIds(ScimUser.field(op.value(), "members")));
        }
        continue;
      }
      Matcher single = MEMBER_PATH.matcher(path);
      if (single.matches() && op.op().equals("remove")) {
        removeMembers(id, List.of(parseId(single.group(1))));
      } else if (path.equalsIgnoreCase("members")) {
        applyMembers(
            tenant,
            id,
            op.op(),
            op.op().equals("remove") && op.value() == null ? null : memberIds(op.value()));
      } else if (path.equalsIgnoreCase("displayName") && !op.op().equals("remove")) {
        displayName =
            ScimUser.text(
                op.value().isObject() ? op.value() : wrap("displayName", op.value()),
                "displayName");
      } else if (path.equalsIgnoreCase("externalId")) {
        externalId = op.op().equals("remove") ? null : op.value().asString();
      } else {
        throw ScimException.invalidPath("cannot change " + path);
      }
    }
    if (displayName == null || displayName.isBlank()) {
      throw ScimException.invalidValue("displayName is required");
    }
    if (!displayName.equalsIgnoreCase(current.displayName())) {
      requireUnique(tenant, displayName, id);
    }
    OffsetDateTime now = OffsetDateTime.now(clock);
    int updated =
        db.update(SCIM_GROUPS)
            .set(SCIM_GROUPS.DISPLAY_NAME, displayName.trim())
            .set(SCIM_GROUPS.DISPLAY_NAME_KEY, displayName.trim().toLowerCase(Locale.ROOT))
            .set(SCIM_GROUPS.EXTERNAL_ID, externalId)
            .set(SCIM_GROUPS.VERSION, current.version() + 1)
            .set(SCIM_GROUPS.UPDATED_AT, now)
            .where(SCIM_GROUPS.ID.eq(id))
            .and(SCIM_GROUPS.VERSION.eq(current.version()))
            .execute();
    if (updated == 0) {
      throw ScimException.preconditionFailed();
    }
    return new Group(
        id, displayName.trim(), externalId, current.version() + 1, current.created(), now);
  }

  void delete(UUID tenant, UUID id) {
    scope.inTenant(tenant, () -> deleteInScope(tenant, id));
  }

  private void deleteInScope(UUID tenant, UUID id) {
    int deleted =
        db.deleteFrom(SCIM_GROUPS)
            .where(SCIM_GROUPS.TENANT_ID.eq(tenant))
            .and(SCIM_GROUPS.ID.eq(id))
            .execute();
    if (deleted == 0) {
      throw ScimException.notFound("no such group");
    }
  }

  ObjectNode toJson(UUID tenant, Group group) {
    return scope.inTenant(tenant, () -> toJsonInScope(group));
  }

  private ObjectNode toJsonInScope(Group group) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    out.putArray("schemas").add(ScimDiscoveryController.GROUP_SCHEMA);
    out.put("id", group.id().toString());
    if (group.externalId() != null) {
      out.put("externalId", group.externalId());
    }
    out.put("displayName", group.displayName());
    var members = out.putArray("members");
    db.select(SCIM_USERS.ID, SCIM_USERS.USER_NAME)
        .from(SCIM_GROUP_MEMBERS)
        .join(SCIM_USERS)
        .on(SCIM_USERS.ID.eq(SCIM_GROUP_MEMBERS.USER_ID))
        .where(SCIM_GROUP_MEMBERS.GROUP_ID.eq(group.id()))
        .orderBy(SCIM_USERS.USER_NAME_KEY)
        .forEach(
            r ->
                members
                    .addObject()
                    .put("value", r.value1().toString())
                    .put("display", r.value2())
                    .put("$ref", "/scim/v2/Users/" + r.value1()));
    ObjectNode meta = out.putObject("meta");
    meta.put("resourceType", "Group");
    meta.put("created", group.created().toString());
    meta.put("lastModified", group.lastModified().toString());
    meta.put("location", "/scim/v2/Groups/" + group.id());
    meta.put("version", group.etag());
    return out;
  }

  private void applyMembers(UUID tenant, UUID group, String op, List<UUID> ids) {
    switch (op) {
      case "add" -> addMembers(tenant, group, ids);
      case "remove" -> {
        if (ids == null) {
          db.deleteFrom(SCIM_GROUP_MEMBERS).where(SCIM_GROUP_MEMBERS.GROUP_ID.eq(group)).execute();
        } else {
          removeMembers(group, ids);
        }
      }
      default -> { // replace
        db.deleteFrom(SCIM_GROUP_MEMBERS).where(SCIM_GROUP_MEMBERS.GROUP_ID.eq(group)).execute();
        addMembers(tenant, group, ids);
      }
    }
  }

  private void addMembers(UUID tenant, UUID group, List<UUID> ids) {
    if (ids.isEmpty()) {
      return;
    }
    Set<UUID> wanted = new LinkedHashSet<>(ids);
    int known =
        db.fetchCount(SCIM_USERS, SCIM_USERS.TENANT_ID.eq(tenant).and(SCIM_USERS.ID.in(wanted)));
    if (known != wanted.size()) {
      // Members of other tenants are as unknown as members that do not exist.
      throw ScimException.invalidValue("every member must be a user of this tenant");
    }
    var insert =
        db.insertInto(
            SCIM_GROUP_MEMBERS,
            SCIM_GROUP_MEMBERS.TENANT_ID,
            SCIM_GROUP_MEMBERS.GROUP_ID,
            SCIM_GROUP_MEMBERS.USER_ID);
    for (UUID user : wanted) {
      insert = insert.values(tenant, group, user);
    }
    insert.onConflictDoNothing().execute();
  }

  private void removeMembers(UUID group, List<UUID> ids) {
    db.deleteFrom(SCIM_GROUP_MEMBERS)
        .where(SCIM_GROUP_MEMBERS.GROUP_ID.eq(group))
        .and(SCIM_GROUP_MEMBERS.USER_ID.in(ids))
        .execute();
  }

  private void requireUnique(UUID tenant, String displayName, UUID except) {
    Condition condition =
        SCIM_GROUPS
            .TENANT_ID
            .eq(tenant)
            .and(SCIM_GROUPS.DISPLAY_NAME_KEY.eq(displayName.trim().toLowerCase(Locale.ROOT)));
    if (except != null) {
      condition = condition.and(SCIM_GROUPS.ID.ne(except));
    }
    if (db.fetchExists(SCIM_GROUPS, condition)) {
      throw ScimException.uniqueness("group " + displayName + " already exists");
    }
  }

  private static List<UUID> memberIds(JsonNode members) {
    if (members == null) {
      return List.of();
    }
    if (!members.isArray()) {
      throw ScimException.invalidValue("members must be a list");
    }
    List<UUID> ids = new ArrayList<>();
    for (JsonNode member : members) {
      String value = ScimUser.text(member, "value");
      if (value == null) {
        throw ScimException.invalidValue("every member needs a value");
      }
      ids.add(parseId(value));
    }
    return ids;
  }

  private static UUID parseId(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw ScimException.invalidValue("member " + value + " is not a user id");
    }
  }

  private static JsonNode wrap(String name, JsonNode value) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.set(name, value);
    return node;
  }

  private static Group map(ScimGroupsRecord r) {
    return new Group(
        r.getId(),
        r.getDisplayName(),
        r.getExternalId(),
        r.getVersion(),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }
}
