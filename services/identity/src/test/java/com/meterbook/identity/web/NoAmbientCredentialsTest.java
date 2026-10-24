package com.meterbook.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.meterbook.identity.security.Role;
import com.meterbook.identity.security.TestCallers;
import com.meterbook.identity.support.TestDatabase;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * CSRF protection is off in every security chain because nothing here authenticates by cookie: the
 * API takes credentials only from the Authorization header, which a browser never attaches on its
 * own (CodeQL's java/spring-disabled-csrf-protection is excluded for that reason,
 * .github/codeql/codeql-config.yml). These tests keep that true.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestDatabase.class)
class NoAmbientCredentialsTest {

  @Autowired MockMvc mvc;

  private static void noSessionOrCookie(MvcResult result) {
    assertThat(result.getRequest().getSession(false)).as("no HTTP session").isNull();
    assertThat(result.getResponse().getHeaders("Set-Cookie")).as("no cookie").isEmpty();
  }

  @Test
  void an_authenticated_request_creates_no_session_and_sets_no_cookie() throws Exception {
    var admin = TestCallers.tenantUser(UUID.randomUUID(), UUID.randomUUID(), Role.TENANT_ADMIN);

    noSessionOrCookie(
        mvc.perform(get("/v1/me").with(authentication(admin)))
            .andExpect(status().isOk())
            .andReturn());
  }

  @Test
  void a_cookie_alone_authenticates_nothing_on_any_chain() throws Exception {
    // A browser would attach this to a cross-site request; it must not count as a credential.
    Cookie session = new Cookie("JSESSIONID", "stolen-or-forged");
    for (String path : List.of("/v1/me", "/v1/api-key", "/scim/v2/Users")) {
      noSessionOrCookie(
          mvc.perform(get(path).cookie(session)).andExpect(status().isUnauthorized()).andReturn());
    }
    noSessionOrCookie(
        mvc.perform(
                post("/v1/projects")
                    .cookie(session)
                    .contentType("application/json")
                    .content("{\"name\":\"x\",\"slug\":\"x\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andReturn());
  }
}
