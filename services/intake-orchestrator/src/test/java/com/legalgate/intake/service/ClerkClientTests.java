package com.legalgate.intake.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.legalgate.intake.config.IntakeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ClerkClientTests {
    private static final String BASE_URL = "https://clerk.example.test/v1";

    private MockRestServiceServer server;

    private ClerkClient client() {
        IntakeProperties properties = mock(IntakeProperties.class);
        when(properties.clerkApiBaseUrl()).thenReturn(BASE_URL);
        when(properties.clerkSecretKey()).thenReturn("sk_test");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new ClerkClient(builder, properties);
    }

    @Test
    void userEmailMapsProviderFailuresToUnavailable() {
        ClerkClient client = client();
        server.expect(requestTo(BASE_URL + "/users/user_1")).andRespond(withServerError());

        assertThat(client.userEmail("user_1")).isEmpty();
        server.verify();
    }

    @Test
    void userEmailPrefersThePrimaryAddressOverTheFirstOne() {
        ClerkClient client = client();
        server.expect(requestTo(BASE_URL + "/users/user_1"))
                .andRespond(withSuccess("""
                        {
                          "primary_email_address_id": "idn_2",
                          "email_addresses": [
                            {"id": "idn_1", "email_address": "old@example.com"},
                            {"id": "idn_2", "email_address": "Payer@Example.com"}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.userEmail("user_1")).contains("payer@example.com");
        server.verify();
    }

    @Test
    void organizationMembershipIdsReadsTheNestedOrganization() {
        ClerkClient client = client();
        server.expect(requestTo(BASE_URL + "/users/user_1/organization_memberships?limit=100&offset=0"))
                .andRespond(withSuccess("""
                        {"data": [{"id": "orgmem_1", "organization": {"id": "org_1"}}], "total_count": 1}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.organizationMembershipIds("user_1")).containsExactly("org_1");
        server.verify();
    }

    @Test
    void createOrganizationNamesTheCreatorSoClerkGrantsTheAdminRole() {
        ClerkClient client = client();
        server.expect(requestTo(BASE_URL + "/organizations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.name").value("Firma Nueva"))
                .andExpect(jsonPath("$.created_by").value("user_1"))
                .andRespond(withSuccess("{\"id\": \"org_created\"}", MediaType.APPLICATION_JSON));

        assertThat(client.createOrganization("Firma Nueva", "user_1")).isEqualTo("org_created");
        server.verify();
    }
}
