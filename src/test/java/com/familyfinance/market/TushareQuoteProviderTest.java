package com.familyfinance.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class TushareQuoteProviderTest {

    @Test
    void postsNormalizedSymbolsAndMapsItemsByDeclaredFieldName() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TushareQuoteProvider provider = new TushareQuoteProvider(builder, JsonMapper.builder().build(), "test-token");
        server.expect(requestTo("https://api.tushare.pro"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.api_name").value("daily"))
                .andExpect(jsonPath("$.token").value("test-token"))
                .andExpect(jsonPath("$.params.ts_code").value("000001.SZ,600000.SH"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"fields":["close","ts_code","trade_date","high","low","open","pre_close","pct_chg"],
                        "items":[[10.25,"000001.SZ","20260903",10.50,10.00,10.10,10.00,2.5]]}}
                        """, MediaType.APPLICATION_JSON));

        DailyQuote quote = provider.fetchDaily(Set.of("600000.sh", "000001.sz")).get(0);
        assertThat(quote.symbol()).isEqualTo("000001.SZ");
        assertThat(quote.closeCents()).isEqualTo(1025L);
        assertThat(quote.tradeDate()).hasToString("2026-09-03");
        server.verify();
    }

    @Test
    void permissionFailuresAndMissingTokenExposeOnlyGenericState() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TushareQuoteProvider provider = new TushareQuoteProvider(builder, JsonMapper.builder().build(), "secret-token");
        server.expect(requestTo("https://api.tushare.pro"))
                .andRespond(withSuccess("{\"code\":2002,\"msg\":\"not permitted\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.fetchDaily(Set.of("000001.SZ")))
                .isInstanceOf(MarketProviderException.class)
                .hasMessage("MARKET_PERMISSION_DENIED")
                .doesNotHaveToString("secret-token");
        server.verify();

        TushareQuoteProvider disabled = new TushareQuoteProvider(RestClient.builder(), JsonMapper.builder().build(), " ");
        assertThat(disabled.available()).isFalse();
        assertThatThrownBy(() -> disabled.fetchDaily(Set.of("000001.SZ")))
                .isInstanceOf(MarketProviderException.class).hasMessage("MARKET_DISABLED");
    }
}
