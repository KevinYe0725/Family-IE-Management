package com.familyfinance.ledger.recurring;

import java.util.List;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Read the public confirmation snapshot, exactly as an API client does. */
public final class RecurringReviewFixture {
    private static final ObjectMapper JSON = new ObjectMapper();
    private RecurringReviewFixture() {}
    public static String token(MockMvc mvc, MockHttpSession session, long id) throws Exception {
        for (int page = 0; ; page++) {
            var response = mvc.perform(get("/api/recurring-occurrences").session(session)
                    .param("page", Integer.toString(page)).param("size", "50")).andReturn().getResponse();
            for (var item : JSON.readTree(response.getContentAsString()).path("data")) {
                if (item.path("id").asLong() == id) return item.path("confirmationToken").asText("");
            }
            if (!Boolean.parseBoolean(response.getHeader("X-Has-Next"))) return "";
        }
    }
    public static String body(MockMvc mvc, MockHttpSession session, long id, String amount) throws Exception {
        var body = JSON.createObjectNode().put("confirmationToken", token(mvc, session, id));
        if (amount != null) body.put("amount", amount);
        return JSON.writeValueAsString(body);
    }
    public static String batchBody(MockMvc mvc, MockHttpSession session, List<Long> ids) throws Exception {
        var body = JSON.createObjectNode();
        var values = body.putArray("occurrenceIds");
        var tokens = body.putObject("confirmationTokens");
        for (Long id : ids) { values.add(id); tokens.put(id.toString(), token(mvc, session, id)); }
        return JSON.writeValueAsString(body);
    }
}
