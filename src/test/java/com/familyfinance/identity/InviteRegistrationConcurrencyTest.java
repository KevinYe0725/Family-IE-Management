package com.familyfinance.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.familyfinance.family.HouseholdMembershipRepository;
import com.familyfinance.family.MembershipStatus;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
class InviteRegistrationConcurrencyTest {
    @Autowired MockMvc mvc;
    @Autowired RegistrationService registrations;
    @Autowired HouseholdMembershipRepository memberships;

    @Test
    void maxUseOneInviteAllowsExactlyOneConcurrentJoin() throws Exception {
        String token = createSingleUseInvite();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> results = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                ready.countDown(); start.await();
                try {
                    registrations.join(new RegistrationService.JoinRegistration("race" + index + "@example.com", "并发成员", "family-pass-2026", token));
                    return "SUCCESS";
                } catch (com.familyfinance.shared.ResourceConflictException exception) { return exception.code(); }
            })).toList();
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> outcomes = List.of(results.get(0).get(), results.get(1).get());
            assertThat(outcomes).containsExactlyInAnyOrder("SUCCESS", "INVITE_EXHAUSTED");
            assertThat(memberships.findByUserIdAndStatus(2L, MembershipStatus.ACTIVE).size()
                    + memberships.findByUserIdAndStatus(3L, MembershipStatus.ACTIVE).size()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    private String createSingleUseInvite() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").with(csrf()).param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession owner = (MockHttpSession) login.getRequest().getSession(false);
        String body = mvc.perform(post("/api/family/invites").session(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxUses\":1}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new tools.jackson.databind.ObjectMapper().readTree(body).path("data").path("token").asText();
    }
}
