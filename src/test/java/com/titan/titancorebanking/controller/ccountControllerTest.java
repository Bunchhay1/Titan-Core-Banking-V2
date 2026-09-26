package com.titan.titancorebanking.controller;

import com.titan.titancorebanking.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean; // ✅ Correct Import
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccountControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean // ✅ Changed from @MockitoBean
    private AccountService accountService;

    @Test
    @WithMockUser
    void getAccounts_ShouldReturnOk() throws Exception {
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk());
    }
}