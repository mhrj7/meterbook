package com.meterbook.identity.scim;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterbook.identity.support.FakeDirectory;
import com.meterbook.identity.support.TestDatabase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestDatabase.class, ScimGroupsApiTest.Fixture.class})
class ScimGroupsApiTest {

  @TestConfiguration
  static class Fixture {
    @Bean
    @Primary
    FakeDirectory fakeDirectory() {
      return new FakeDirectory();
    }
  }

  private static final String SCIM = "application/scim+json";

  @Autowired MockMvc mvc;
  @Autowired DSLContext db;
  @Autowired ScimTokens tokens;
  @Autowired JsonMapper json;

  private ScimTestSupport acme;
  private String jane;
  private String joe;

  @BeforeEach
  void setUp() throws Exception {
    acme = ScimTestSupport.tenant(db, tokens);
    jane = user(acme, "jane");
    joe = user(acme, "joe");
  }

  private MockHttpServletRequestBuilder as(
      ScimTestSupport tenant, MockHttpServletRequestBuilder request) {
    return request.header("Authorization", "Bearer " + tenant.token());
  }

  private String user(ScimTestSupport tenant, String local) throws Exception {
    String body =
        mvc.perform(
                as(tenant, post("/scim/v2/Users")).contentType(SCIM).content(tenant.user(local)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(body).get("id").asString();
  }

  private ResultActions createGroup(String name, String... members) throws Exception {
    StringBuilder list = new StringBuilder();
    for (String m : members) {
      list.append(list.isEmpty() ? "" : ",").append("{\"value\":\"").append(m).append("\"}");
    }
    return mvc.perform(
        as(acme, post("/scim/v2/Groups"))
            .contentType(SCIM)
            .content(
                "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:Group\"],\"displayName\":\""
                    + name
                    + "\",\"externalId\":\"g-"
                    + name
                    + "\",\"members\":["
                    + list
                    + "]}"));
  }

  private String groupId(String name, String... members) throws Exception {
    return json.readTree(createGroup(name, members).andReturn().getResponse().getContentAsString())
        .get("id")
        .asString();
  }

  private ResultActions patchGroup(String id, String operations, String ifMatch) throws Exception {
    var request =
        as(acme, patch("/scim/v2/Groups/" + id))
            .contentType(SCIM)
            .content(
                "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],\"Operations\":["
                    + operations
                    + "]}");
    return mvc.perform(ifMatch == null ? request : request.header("If-Match", ifMatch));
  }

  @Test
  void creates_a_group_with_members_and_shows_it_on_the_users() throws Exception {
    String id =
        json.readTree(
                createGroup("Finance", jane)
                    .andExpect(status().isCreated())
                    .andExpect(header().string("ETag", "W/\"1\""))
                    .andExpect(jsonPath("$.members[0].value").value(jane))
                    .andExpect(jsonPath("$.members[0].display").value(acme.email("jane")))
                    .andExpect(jsonPath("$.meta.resourceType").value("Group"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("id")
            .asString();

    mvc.perform(as(acme, get("/scim/v2/Users/" + jane)))
        .andExpect(jsonPath("$.groups[0].value").value(id))
        .andExpect(jsonPath("$.groups[0].display").value("Finance"));
  }

  @Test
  void members_must_be_users_of_the_same_tenant() throws Exception {
    ScimTestSupport globex = ScimTestSupport.tenant(db, tokens);
    String outsider = user(globex, "eve");

    createGroup("Finance", outsider)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.scimType").value("invalidValue"));
    createGroup("Finance", "not-a-uuid").andExpect(jsonPath("$.scimType").value("invalidValue"));
    createGroup("Finance", java.util.UUID.randomUUID().toString())
        .andExpect(status().isBadRequest());
  }

  @Test
  void display_names_are_unique_per_tenant_ignoring_case() throws Exception {
    createGroup("Finance").andExpect(status().isCreated());
    createGroup("FINANCE")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.scimType").value("uniqueness"));
    mvc.perform(
            as(acme, post("/scim/v2/Groups")).contentType(SCIM).content("{\"displayName\":\" \"}"))
        .andExpect(jsonPath("$.scimType").value("invalidValue"));
  }

  @Test
  void adds_and_removes_members_the_ways_clients_do() throws Exception {
    String id = groupId("Finance", jane);

    patchGroup(
            id,
            "{\"op\":\"add\",\"path\":\"members\",\"value\":[{\"value\":\"" + joe + "\"}]}",
            "W/\"1\"")
        .andExpect(jsonPath("$.members.length()").value(2))
        .andExpect(header().string("ETag", "W/\"2\""));
    patchGroup(id, "{\"op\":\"remove\",\"path\":\"members[value eq \\\"" + jane + "\\\"]\"}", null)
        .andExpect(jsonPath("$.members.length()").value(1));
    patchGroup(
            id,
            "{\"op\":\"replace\",\"path\":\"members\",\"value\":[{\"value\":\"" + jane + "\"}]}",
            null)
        .andExpect(jsonPath("$.members[0].value").value(jane));
    patchGroup(
            id,
            "{\"op\":\"remove\",\"path\":\"members\",\"value\":[{\"value\":\"" + jane + "\"}]}",
            null)
        .andExpect(jsonPath("$.members").isEmpty());
    patchGroup(id, "{\"op\":\"add\",\"value\":{\"members\":[{\"value\":\"" + joe + "\"}]}}", null)
        .andExpect(jsonPath("$.members.length()").value(1));
    patchGroup(id, "{\"op\":\"remove\",\"path\":\"members\"}", null)
        .andExpect(jsonPath("$.members").isEmpty());
  }

  @Test
  void renames_and_validates() throws Exception {
    String id = groupId("Finance");
    groupId("Ops");

    patchGroup(id, "{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"Accounting\"}", null)
        .andExpect(jsonPath("$.displayName").value("Accounting"));
    patchGroup(
            id,
            "{\"op\":\"replace\",\"value\":{\"displayName\":\"Treasury\",\"externalId\":\"t1\"}}",
            null)
        .andExpect(jsonPath("$.displayName").value("Treasury"))
        .andExpect(jsonPath("$.externalId").value("t1"));
    patchGroup(id, "{\"op\":\"remove\",\"path\":\"externalId\"}", null)
        .andExpect(jsonPath("$.externalId").doesNotExist());
    patchGroup(id, "{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"ops\"}", null)
        .andExpect(status().isConflict());
    patchGroup(id, "{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"\"}", null)
        .andExpect(jsonPath("$.scimType").value("invalidValue"));
    patchGroup(id, "{\"op\":\"replace\",\"path\":\"owner\",\"value\":\"x\"}", null)
        .andExpect(jsonPath("$.scimType").value("invalidPath"));
    patchGroup(id, "{\"op\":\"replace\",\"value\":\"x\"}", null)
        .andExpect(jsonPath("$.scimType").value("invalidValue"));
    patchGroup(id, "{\"op\":\"replace\",\"path\":\"displayName\",\"value\":\"Again\"}", "W/\"1\"")
        .andExpect(status().isPreconditionFailed());
  }

  @Test
  void lists_gets_and_deletes_groups_of_the_tenant_only() throws Exception {
    String id = groupId("Finance", jane);
    ScimTestSupport globex = ScimTestSupport.tenant(db, tokens);

    mvc.perform(as(acme, get("/scim/v2/Groups").param("filter", "displayName eq \"finance\"")))
        .andExpect(jsonPath("$.totalResults").value(1));
    mvc.perform(as(acme, get("/scim/v2/Groups").param("filter", "externalId eq \"g-Finance\"")))
        .andExpect(jsonPath("$.totalResults").value(1));
    mvc.perform(as(globex, get("/scim/v2/Groups"))).andExpect(jsonPath("$.totalResults").value(0));
    mvc.perform(as(globex, get("/scim/v2/Groups/" + id))).andExpect(status().isNotFound());
    mvc.perform(as(globex, delete("/scim/v2/Groups/" + id))).andExpect(status().isNotFound());
    mvc.perform(as(acme, get("/scim/v2/Groups/" + id))).andExpect(status().isOk());

    mvc.perform(as(acme, delete("/scim/v2/Groups/" + id))).andExpect(status().isNoContent());
    mvc.perform(as(acme, get("/scim/v2/Users/" + jane))).andExpect(jsonPath("$.groups").isEmpty());
  }

  @Test
  void deleting_a_user_removes_them_from_groups() throws Exception {
    String id = groupId("Finance", jane, joe);

    mvc.perform(as(acme, delete("/scim/v2/Users/" + jane))).andExpect(status().isNoContent());

    mvc.perform(as(acme, get("/scim/v2/Groups/" + id)))
        .andExpect(jsonPath("$.members.length()").value(1))
        .andExpect(jsonPath("$.members[0].value").value(joe));
  }
}
