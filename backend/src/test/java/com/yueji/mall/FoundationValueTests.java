package com.yueji.mall;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.yueji.mall.common.api.ApiId;
import com.yueji.mall.common.api.ApiMoney;
import com.yueji.mall.common.api.PageData;
import com.yueji.mall.common.api.PageQuery;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FoundationValueTests {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void idNeverTravelsAsANumberAndSupportsUnsignedBigint() throws Exception {
        var id = mapper.readValue("\"18446744073709551615\"", ApiId.class);
        assertThat(mapper.writeValueAsString(id)).isEqualTo("\"18446744073709551615\"");
        for (String invalid : List.of("9007199254740993", "\"18446744073709551616\"", "\"0\"", "\"01\"")) {
            assertThatThrownBy(() -> mapper.readValue(invalid, ApiId.class)).isInstanceOf(Exception.class);
        }
    }

    @Test
    void moneyPreservesDecimalPrecisionAndRejectsNumericCoercion() throws Exception {
        assertThat(mapper.writeValueAsString(mapper.readValue("\"19.90\"", ApiMoney.class))).isEqualTo("\"19.90\"");
        assertThat(mapper.writeValueAsString(mapper.readValue("\"-19.90\"", ApiMoney.class))).isEqualTo("\"-19.90\"");
        for (String invalid : List.of("19.90", "\"19.9\"", "\"1e2\"", "\"10000000000.00\"", "\"1.001\"")) {
            assertThatThrownBy(() -> mapper.readValue(invalid, ApiMoney.class)).isInstanceOf(Exception.class);
        }
    }

    @Test
    void paginationHasOneDefaultAndRejectsUnboundedPageSize() {
        assertThat(new PageQuery(null, null, null).page()).isEqualTo(1);
        assertThat(new PageQuery(null, null, null).pageSize()).isEqualTo(20);
        assertThat(new PageQuery(Integer.MAX_VALUE, 100, null).offset()).isEqualTo(214748364600L);
        assertThatThrownBy(() -> new PageQuery(0, 20, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageQuery(1, 101, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageData<>(List.of("a"), 1, 20, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
