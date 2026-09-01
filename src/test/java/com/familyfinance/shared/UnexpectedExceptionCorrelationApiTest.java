package com.familyfinance.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@Import(UnexpectedExceptionCorrelationApiTest.FailingController.class)
class UnexpectedExceptionCorrelationApiTest {

    @Autowired
    MockMvc mvc;

    @Test
    void unexpectedExceptionReturnsGeneric500AndLogsThePropagatedRequestId() throws Exception {
        String secret = "must-not-appear-in-logs";
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        boolean originalAdditive = logger.isAdditive();
        appender.start();
        logger.setAdditive(false);
        logger.addAppender(appender);

        MvcResult result;
        try {
            result = mvc.perform(get("/api/test/unexpected")
                            .session(login())
                            .header("X-Test-Secret", secret))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.error.message").value("服务器暂时无法处理请求"))
                    .andReturn();
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(originalAdditive);
            appender.stop();
        }

        String requestId = result.getResponse().getHeader("X-Request-ID");
        assertThat(requestId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("deliberate unexpected failure")
                .doesNotContain(secret);

        List<ILoggingEvent> events = appender.list;
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains(requestId).doesNotContain(secret);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
        });
    }

    private MockHttpSession login() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .param("username", "demo")
                        .param("password", "demo1234"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    @RestController
    static class FailingController {

        @GetMapping("/api/test/unexpected")
        ApiEnvelope<Void> fail() {
            throw new IllegalStateException("deliberate unexpected failure");
        }
    }
}
