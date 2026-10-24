package com.meterbook.identity.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ScimParsingTest {

  private final JsonMapper json = JsonMapper.builder().build();
  private static final Set<String> ATTRIBUTES = Set.of("userName", "externalId");

  @Test
  void parses_equality_filters_case_insensitively_with_escapes() {
    assertThat(ScimFilter.parse("USERNAME Eq \"a\\\"b@x.test\"", ATTRIBUTES))
        .contains(new ScimFilter("userName", "a\"b@x.test"));
    assertThat(ScimFilter.parse(null, ATTRIBUTES)).isEmpty();
    assertThat(ScimFilter.parse("  ", ATTRIBUTES)).isEmpty();
  }

  @Test
  void rejects_everything_else_instead_of_returning_all_resources() {
    for (String filter :
        new String[] {
          "userName pr",
          "userName eq x",
          "userName eq \"a\" and externalId eq \"b\"",
          "emails.value eq \"a\"",
          "userName co \"a\""
        }) {
      assertThatThrownBy(() -> ScimFilter.parse(filter, ATTRIBUTES))
          .isInstanceOf(ScimException.class);
    }
  }

  @Test
  void a_user_takes_its_email_from_the_primary_entry_then_the_first_then_the_user_name() {
    var primary =
        ScimUser.Attributes.from(
            json.readTree(
                "{\"userName\":\"u@x.test\",\"emails\":[{\"value\":\"a@x.test\"},{\"value\":\"B@x.test\",\"primary\":\"True\"}]}"));
    var first =
        ScimUser.Attributes.from(
            json.readTree(
                "{\"userName\":\"u@x.test\",\"emails\":[{\"type\":\"home\"},{\"value\":\"a@x.test\"}]}"));
    var none =
        ScimUser.Attributes.from(json.readTree("{\"username\":\"U@X.test\",\"Active\":false}"));

    assertThat(primary.email()).isEqualTo("b@x.test");
    assertThat(first.email()).isEqualTo("a@x.test");
    assertThat(none.email()).isEqualTo("u@x.test");
    assertThat(none.active()).isFalse();
    assertThat(none.userNameKey()).isEqualTo("u@x.test");
  }

  @Test
  void rejects_malformed_users() {
    for (String body :
        new String[] {
          "{\"userName\":\"u@x\",\"emails\":{}}",
          "{\"userName\":\"u@x\",\"active\":1}",
          "{\"userName\":\"u@x\",\"name\":{\"givenName\":3}}"
        }) {
      assertThatThrownBy(() -> ScimUser.Attributes.from(json.readTree(body)))
          .isInstanceOf(ScimException.class);
    }
  }

  @Test
  void patch_requests_need_the_patchop_schema_ops_and_paths() {
    assertThatThrownBy(
            () ->
                ScimPatch.operations(
                    json.readTree(
                        "{\"schemas\":[\"x\"],\"Operations\":[{\"op\":\"add\",\"value\":1}]}")))
        .hasMessageContaining("PatchOp");
    String schema = "\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"]";
    assertThatThrownBy(
            () ->
                ScimPatch.operations(
                    json.readTree("{" + schema + ",\"Operations\":[{\"value\":1}]}")))
        .hasMessageContaining("op");
    assertThatThrownBy(
            () ->
                ScimPatch.operations(
                    json.readTree("{" + schema + ",\"Operations\":[{\"op\":\"add\"}]}")))
        .hasMessageContaining("value");
    assertThatThrownBy(
            () ->
                ScimPatch.operations(
                    json.readTree("{" + schema + ",\"Operations\":[{\"op\":\"remove\"}]}")))
        .hasMessageContaining("path");
    assertThatThrownBy(() -> ScimPatch.operations(json.readTree("[]")))
        .isInstanceOf(ScimException.class);
  }

  @Test
  void user_patches_cover_names_and_removals() {
    var current =
        ScimUser.Attributes.from(
            json.readTree(
                "{\"userName\":\"u@x.test\",\"displayName\":\"U\",\"name\":{\"givenName\":\"A\",\"familyName\":\"B\"}}"));
    String schema = "\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"]";
    var ops =
        ScimPatch.operations(
            json.readTree(
                "{"
                    + schema
                    + ",\"Operations\":["
                    + "{\"op\":\"replace\",\"path\":\"name\",\"value\":{\"familyName\":\"C\"}},"
                    + "{\"op\":\"remove\",\"path\":\"displayName\"},"
                    + "{\"op\":\"remove\",\"path\":\"name.givenName\"},"
                    + "{\"op\":\"replace\",\"path\":\"userName\",\"value\":\"v@x.test\"},"
                    + "{\"op\":\"remove\",\"path\":\"emails\"}]}"));

    var next = ScimPatch.applyToUser(current, ops);

    assertThat(next.familyName()).isEqualTo("C");
    assertThat(next.givenName()).isNull();
    assertThat(next.displayName()).isNull();
    assertThat(next.userName()).isEqualTo("v@x.test");
    assertThat(next.email()).isEqualTo("v@x.test");
    assertThatThrownBy(
            () ->
                ScimPatch.applyToUser(
                    current,
                    ScimPatch.operations(
                        json.readTree(
                            "{"
                                + schema
                                + ",\"Operations\":[{\"op\":\"remove\",\"path\":\"active\"}]}"))))
        .isInstanceOf(ScimException.class);
    assertThatThrownBy(
            () ->
                ScimPatch.applyToUser(
                    current,
                    ScimPatch.operations(
                        json.readTree(
                            "{"
                                + schema
                                + ",\"Operations\":[{\"op\":\"replace\",\"path\":\"name\",\"value\":\"x\"}]}"))))
        .isInstanceOf(ScimException.class);
    assertThatThrownBy(
            () ->
                ScimPatch.applyToUser(
                    current,
                    ScimPatch.operations(
                        json.readTree(
                            "{"
                                + schema
                                + ",\"Operations\":[{\"op\":\"replace\",\"path\":\"userName\",\"value\":\" \"}]}"))))
        .isInstanceOf(ScimException.class);
    assertThatThrownBy(
            () ->
                ScimPatch.applyToUser(
                    current,
                    ScimPatch.operations(
                        json.readTree(
                            "{"
                                + schema
                                + ",\"Operations\":[{\"op\":\"remove\",\"path\":\"nickName\"}]}"))))
        .isInstanceOf(ScimException.class);
  }
}
