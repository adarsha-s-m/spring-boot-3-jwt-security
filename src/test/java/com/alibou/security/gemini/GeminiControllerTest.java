package com.alibou.security.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GeminiController.class)
class GeminiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GeminiService geminiService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithMockUser // For Spring Security context, if applicable
    void queryGemini_success() throws Exception {
        GeminiRequest request = new GeminiRequest("test query");

        StreamingResponseBody mockStream = outputStream -> {
            outputStream.write("Streamed".getBytes(StandardCharsets.UTF_8));
            outputStream.write(" content".getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        };

        when(geminiService.streamQuery(anyString())).thenReturn(mockStream);

        mockMvc.perform(post("/api/v1/gemini/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf())) // If CSRF is enabled
                .andExpect(status().isOk())
                .andExpect(content().string("Streamed content"));
    }

    @Test
    @WithMockUser
    void queryGemini_serviceReturnsErrorStream() throws Exception {
        GeminiRequest request = new GeminiRequest("error query");

        StreamingResponseBody errorStream = outputStream -> {
            outputStream.write("Error: Service failure".getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        };

        when(geminiService.streamQuery(anyString())).thenReturn(errorStream);

        mockMvc.perform(post("/api/v1/gemini/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isOk()) // The controller itself is OK, the error is in the stream
                .andExpect(content().string("Error: Service failure"));
    }
}
