package com.cs6650.a3creditcardservice.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CreditCardController.class)
@ExtendWith(OutputCaptureExtension.class)
class CreditCardControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void authorize_invalidFormat_returns400() throws Exception {
        mvc.perform(authorize("{\"credit_card_number\": \"4111111111111111\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authorize_missingCardNumber_returns400() throws Exception {
        mvc.perform(authorize("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authorize_validCard_approvesAbout90Percent() throws Exception {
        int trials = 1000;
        int approved = 0;
        for (int i = 0; i < trials; i++) {
            int status = mvc.perform(authorize("{\"credit_card_number\": \"1234-5678-9012-3456\"}"))
                    .andReturn().getResponse().getStatus();
            assertThat(status).isIn(200, 402);
            if (status == 200) {
                approved++;
            }
        }
        // Mean 900, standard deviation ~9.5: this range is more than 5 standard deviations wide
        assertThat(approved).isBetween(850, 950);
    }

    @Test
    void authorize_validCard_logsOnlyMaskedNumber(CapturedOutput output) throws Exception {
        // Enough calls to hit both the AUTHORIZED and DECLINED branches
        for (int i = 0; i < 100; i++) {
            mvc.perform(authorize("{\"credit_card_number\": \"4000-0000-0000-0002\"}"));
        }
        assertThat(output.getOut()).contains("****-****-****-0002");
        assertThat(output.getOut()).doesNotContain("4000-0000-0000-0002");
    }

    @Test
    void maskCardNumber_keepsOnlyLastFourDigits() {
        assertThat(CreditCardController.maskCardNumber("1234-5678-9012-3456"))
                .isEqualTo("****-****-****-3456");
    }

    private static org.springframework.test.web.servlet.RequestBuilder authorize(String body) {
        return post("/credit-card-authorizer/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
