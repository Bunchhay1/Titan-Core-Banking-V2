package com.titan.titancorebanking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.titan.titancorebanking.dto.request.LoginRequest;
import com.titan.titancorebanking.service.imple.AuthenticationService; // ✅ Explicit Import
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean; // ✅ Use MockBean for Spring Boot < 3.4
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean // ✅ Fixes MockitoBean missing error
    private AuthenticationService authenticationService; // ✅ Matches your actual Service class name

    @Test
    void login_ShouldReturnOk() throws Exception {
        LoginRequest login = new LoginRequest();
        login.setUsername("bunchhay");
        login.setPassword("password");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk());
    }
}