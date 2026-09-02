package com.familyfinance.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CategoryHierarchyConcurrencyApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CategoryRepository categories;

    @Test
    void concurrentOppositeMovesCannotCommitACycle() throws Exception {
        MockHttpSession session = login();
        long first = create(session, "并发父类甲" + System.nanoTime());
        long second = create(session, "并发父类乙" + System.nanoTime());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> firstMove = executor.submit(() -> moveAtBarrier(ready, start, session, first, second));
            Future<MvcResult> secondMove = executor.submit(() -> moveAtBarrier(ready, start, session, second, first));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Integer> statuses = List.of(
                    firstMove.get(8, TimeUnit.SECONDS).getResponse().getStatus(),
                    secondMove.get(8, TimeUnit.SECONDS).getResponse().getStatus());
            assertThat(statuses).containsExactlyInAnyOrder(200, 422);

            Category savedFirst = categories.findById(first).orElseThrow();
            Category savedSecond = categories.findById(second).orElseThrow();
            assertThat(savedFirst.getParent() == null || savedSecond.getParent() == null).isTrue();
            assertThat(!(parentId(savedFirst) == second && parentId(savedSecond) == first)).isTrue();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private MvcResult moveAtBarrier(
            CountDownLatch ready, CountDownLatch start, MockHttpSession session, long id, long parentId) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("moves did not start");
        return mvc.perform(patch("/api/categories/{id}", id).session(session).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"kind":"expense","name":"并发移动-%d","color":"#123456","parentId":%d}
                                """.formatted(id, parentId)))
                .andReturn();
    }

    private long create(MockHttpSession session, String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/categories").session(session).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"kind":"expense","name":"%s","color":"#123456","parentId":null}
                                """.formatted(name)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", "demo").param("password", "demo1234"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static long parentId(Category category) {
        return category.getParent() == null ? -1L : category.getParent().getId();
    }
}
