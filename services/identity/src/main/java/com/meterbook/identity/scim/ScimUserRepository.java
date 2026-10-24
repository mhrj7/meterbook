package com.meterbook.identity.scim;

import static com.meterbook.identity.db.Tables.SCIM_GROUPS;
import static com.meterbook.identity.db.Tables.SCIM_GROUP_MEMBERS;
import static com.meterbook.identity.db.Tables.SCIM_USERS;

import com.meterbook.identity.db.tables.records.ScimUsersRecord;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/** SCIM users. Every method is scoped to one tenant. */
@Repository
class ScimUserRepository {

  private final DSLContext db;

  ScimUserRepository(DSLContext db) {
    this.db = db;
  }

  void insert(UUID tenant, ScimUser user) {
    var a = user.attributes();
    db.insertInto(SCIM_USERS)
        .set(SCIM_USERS.ID, user.id())
        .set(SCIM_USERS.TENANT_ID, tenant)
        .set(SCIM_USERS.USER_NAME, a.userName())
        .set(SCIM_USERS.USER_NAME_KEY, a.userNameKey())
        .set(SCIM_USERS.EXTERNAL_ID, a.externalId())
        .set(SCIM_USERS.KEYCLOAK_USER_ID, user.keycloakUserId())
        .set(SCIM_USERS.EMAIL, a.email())
        .set(SCIM_USERS.GIVEN_NAME, a.givenName())
        .set(SCIM_USERS.FAMILY_NAME, a.familyName())
        .set(SCIM_USERS.DISPLAY_NAME, a.displayName())
        .set(SCIM_USERS.ACTIVE, a.active())
        .set(SCIM_USERS.VERSION, user.version())
        .set(SCIM_USERS.CREATED_AT, user.created())
        .set(SCIM_USERS.UPDATED_AT, user.lastModified())
        .execute();
  }

  /** Writes {@code user} only if the stored version is still {@code expectedVersion}. */
  boolean update(UUID tenant, ScimUser user, long expectedVersion) {
    var a = user.attributes();
    return db.update(SCIM_USERS)
            .set(SCIM_USERS.USER_NAME, a.userName())
            .set(SCIM_USERS.USER_NAME_KEY, a.userNameKey())
            .set(SCIM_USERS.EXTERNAL_ID, a.externalId())
            .set(SCIM_USERS.EMAIL, a.email())
            .set(SCIM_USERS.GIVEN_NAME, a.givenName())
            .set(SCIM_USERS.FAMILY_NAME, a.familyName())
            .set(SCIM_USERS.DISPLAY_NAME, a.displayName())
            .set(SCIM_USERS.ACTIVE, a.active())
            .set(SCIM_USERS.VERSION, user.version())
            .set(SCIM_USERS.UPDATED_AT, user.lastModified())
            .where(SCIM_USERS.TENANT_ID.eq(tenant))
            .and(SCIM_USERS.ID.eq(user.id()))
            .and(SCIM_USERS.VERSION.eq(expectedVersion))
            .execute()
        == 1;
  }

  Optional<ScimUser> find(UUID tenant, UUID id) {
    return db.selectFrom(SCIM_USERS)
        .where(SCIM_USERS.TENANT_ID.eq(tenant))
        .and(SCIM_USERS.ID.eq(id))
        .fetchOptional()
        .map(ScimUserRepository::map);
  }

  boolean userNameExists(UUID tenant, String userNameKey) {
    return db.fetchExists(
        SCIM_USERS, SCIM_USERS.TENANT_ID.eq(tenant).and(SCIM_USERS.USER_NAME_KEY.eq(userNameKey)));
  }

  /** One page (1-based {@code startIndex}) of users matching the filter, and the total count. */
  Map.Entry<Integer, List<ScimUser>> list(
      UUID tenant, Optional<ScimFilter> filter, int startIndex, int count) {
    Condition condition = SCIM_USERS.TENANT_ID.eq(tenant);
    if (filter.isPresent()) {
      condition =
          condition.and(
              switch (filter.get().attribute()) {
                case "userName" ->
                    SCIM_USERS.USER_NAME_KEY.eq(
                        filter.get().value().toLowerCase(java.util.Locale.ROOT));
                case "externalId" -> SCIM_USERS.EXTERNAL_ID.eq(filter.get().value());
                default -> DSL.falseCondition();
              });
    }
    int total = db.fetchCount(SCIM_USERS, condition);
    List<ScimUser> page =
        db.selectFrom(SCIM_USERS)
            .where(condition)
            .orderBy(SCIM_USERS.CREATED_AT, SCIM_USERS.ID)
            .offset(startIndex - 1)
            .limit(count)
            .fetch(ScimUserRepository::map);
    return new AbstractMap.SimpleImmutableEntry<>(total, page);
  }

  List<ScimUser> all(UUID tenant) {
    return db.selectFrom(SCIM_USERS)
        .where(SCIM_USERS.TENANT_ID.eq(tenant))
        .fetch(ScimUserRepository::map);
  }

  /** The groups the user belongs to, as (id, displayName). */
  List<Map.Entry<UUID, String>> groupsOf(UUID user) {
    return db.select(SCIM_GROUPS.ID, SCIM_GROUPS.DISPLAY_NAME)
        .from(SCIM_GROUP_MEMBERS)
        .join(SCIM_GROUPS)
        .on(SCIM_GROUPS.ID.eq(SCIM_GROUP_MEMBERS.GROUP_ID))
        .where(SCIM_GROUP_MEMBERS.USER_ID.eq(user))
        .orderBy(SCIM_GROUPS.DISPLAY_NAME_KEY)
        .fetch(
            r ->
                (Map.Entry<UUID, String>)
                    new AbstractMap.SimpleImmutableEntry<>(r.value1(), r.value2()));
  }

  boolean delete(UUID tenant, UUID id) {
    return db.deleteFrom(SCIM_USERS)
            .where(SCIM_USERS.TENANT_ID.eq(tenant))
            .and(SCIM_USERS.ID.eq(id))
            .execute()
        == 1;
  }

  private static ScimUser map(ScimUsersRecord r) {
    return new ScimUser(
        r.getId(),
        r.getKeycloakUserId(),
        new ScimUser.Attributes(
            r.getUserName(),
            r.getExternalId(),
            r.getEmail(),
            r.getGivenName(),
            r.getFamilyName(),
            r.getDisplayName(),
            r.getActive()),
        r.getVersion(),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }
}
