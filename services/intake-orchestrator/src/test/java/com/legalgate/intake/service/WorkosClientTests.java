package com.legalgate.intake.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

import com.legalgate.intake.config.IntakeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WorkosClientTests {
    @Test
    void userEmailMapsProviderFailuresToUnavailable() {
        IntakeProperties properties = mock(IntakeProperties.class);
        when(properties.workosApiBaseUrl()).thenReturn("https://workos.example.test");
        when(properties.workosApiKey()).thenReturn("sk_test");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WorkosClient client = new WorkosClient(builder, properties);
        server.expect(requestTo("https://workos.example.test/user_management/users/user_1"))
                .andRespond(withServerError());

        assertThat(client.userEmail("user_1")).isEmpty();
        server.verify();
    }
}
