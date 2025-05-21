package com.alibou.security.gemini;

import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.Candidate;
import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.Part;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GeminiServiceTest {

    @InjectMocks
    private GeminiService geminiService;

    // Mocks for SDK classes will be handled via MockedConstruction

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(geminiService, "geminiApiKey", "test-api-key");
        ReflectionTestUtils.setField(geminiService, "projectId", "test-project-id");
        ReflectionTestUtils.setField(geminiService, "location", "test-location");
        ReflectionTestUtils.setField(geminiService, "modelName", "gemini-pro");
    }

    @Test
    void streamQuery_success() throws IOException {
        // Mocking the response stream
        GenerateContentResponse mockResponse1 = GenerateContentResponse.newBuilder()
            .addCandidates(Candidate.newBuilder()
                .setContent(Content.newBuilder().addParts(Part.newBuilder().setText("Hello ")))
                .build())
            .build();
        GenerateContentResponse mockResponse2 = GenerateContentResponse.newBuilder()
            .addCandidates(Candidate.newBuilder()
                .setContent(Content.newBuilder().addParts(Part.newBuilder().setText("World!")))
                .build())
            .build();

        // Mock the iterator for ResponseStream
        @SuppressWarnings("unchecked")
        ResponseStream<GenerateContentResponse> mockResponseStream = mock(ResponseStream.class);
        Iterator<GenerateContentResponse> mockIterator = Arrays.asList(mockResponse1, mockResponse2).iterator();
        when(mockResponseStream.iterator()).thenReturn(mockIterator);


        // Using MockedConstruction for VertexAI and GenerativeModel
        try (MockedConstruction<VertexAI> mockedVertexAI = mockConstruction(VertexAI.class);
             MockedConstruction<GenerativeModel> mockedGenerativeModel = mockConstruction(GenerativeModel.class,
                 (mock, context) -> when(mock.generateContentStream(anyString())).thenReturn(mockResponseStream))) {

            StreamingResponseBody responseBody = geminiService.streamQuery("test query");
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            responseBody.writeTo(outputStream);

            assertEquals("Hello World!", outputStream.toString().trim());

            // Verify VertexAI and GenerativeModel were constructed
            assertEquals(1, mockedVertexAI.constructed().size());
            assertEquals(1, mockedGenerativeModel.constructed().size());
            // Verify generateContentStream was called on the GenerativeModel mock
            verify(mockedGenerativeModel.constructed().get(0)).generateContentStream("test query");
        }
    }

    @Test
    void streamQuery_missingApiKey_returnsError() throws IOException {
        ReflectionTestUtils.setField(geminiService, "geminiApiKey", ""); // Blank API key
        StreamingResponseBody responseBody = geminiService.streamQuery("test query");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        responseBody.writeTo(outputStream);
        assertTrue(outputStream.toString().contains("Error: Gemini API key not configured."));
    }

     @Test
    void streamQuery_geminiApiThrowsException_returnsError() throws IOException {
        try (MockedConstruction<VertexAI> mockedVertexAI = mockConstruction(VertexAI.class);
             MockedConstruction<GenerativeModel> mockedGenerativeModel = mockConstruction(GenerativeModel.class,
                 (mock, context) -> when(mock.generateContentStream(anyString())).thenThrow(new RuntimeException("Gemini API error")))) {

            StreamingResponseBody responseBody = geminiService.streamQuery("test query");
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            responseBody.writeTo(outputStream);

            assertTrue(outputStream.toString().contains("Error: Gemini API error"));
        }
    }
}
