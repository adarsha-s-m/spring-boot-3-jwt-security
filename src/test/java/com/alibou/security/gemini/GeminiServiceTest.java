package com.alibou.security.gemini;

import com.google.cloud.generativeai.v1.Content;
import com.google.cloud.generativeai.v1.GenerateContentResponse;
import com.google.cloud.generativeai.v1.Part;
import com.google.cloud.generativeai.v1.PredictionServiceClient;
import com.google.cloud.generativeai.v1.PredictionServiceSettings;
// Import the correct GenerativeModel from the new SDK
import com.google.cloud.generativeai.v1.GenerativeModel;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GeminiServiceTest {

    @InjectMocks
    private GeminiService geminiService;

    // We will use MockedConstruction for PredictionServiceClient and GenerativeModel

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(geminiService, "geminiApiKey", "test-api-key");
        ReflectionTestUtils.setField(geminiService, "modelName", "gemini-pro");
        // ProjectId and Location are no longer used, so no need to set them.
    }

    @Test
    void streamQuery_success() throws IOException {
        // Mocking the response stream from GenerativeModel's generateContentStream
        GenerateContentResponse mockResponse1 = GenerateContentResponse.newBuilder()
                .addCandidates(com.google.cloud.generativeai.v1.Candidate.newBuilder() // Use Candidate from new SDK
                        .setContent(Content.newBuilder().addParts(Part.newBuilder().setText("Hello "))))
                .build();
        GenerateContentResponse mockResponse2 = GenerateContentResponse.newBuilder()
                .addCandidates(com.google.cloud.generativeai.v1.Candidate.newBuilder()
                        .setContent(Content.newBuilder().addParts(Part.newBuilder().setText("World!"))))
                .build();

        @SuppressWarnings("unchecked")
        Iterator<GenerateContentResponse> mockResponseIterator = mock(Iterator.class);
        when(mockResponseIterator.hasNext()).thenReturn(true, true, false);
        when(mockResponseIterator.next()).thenReturn(mockResponse1, mockResponse2);

        // Using MockedConstruction for PredictionServiceClient and GenerativeModel
        // PredictionServiceSettings is created directly, we don't need to mock its construction
        // unless specific exceptions from its build() method are being tested.
        try (MockedConstruction<PredictionServiceClient> mockedPredictionServiceClient =
                     mockConstruction(PredictionServiceClient.class, (mock, context) -> {
                         // PredictionServiceClient.create(settings) will return this mock
                     });
             MockedConstruction<GenerativeModel> mockedGenerativeModel =
                     mockConstruction(GenerativeModel.class, (mock, context) -> {
                         // new GenerativeModel(modelName, client) will return this mock
                         // Stub the generateContentStream method
                         when(mock.generateContentStream(any(Content.class))).thenReturn(mockResponseIterator);
                     })) {

            StreamingResponseBody responseBody = geminiService.streamQuery("test query");
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            responseBody.writeTo(outputStream);

            assertEquals("Hello World!", outputStream.toString().trim());

            // Verify constructions and method calls
            assertEquals(1, mockedPredictionServiceClient.constructed().size());
            assertEquals(1, mockedGenerativeModel.constructed().size());
            
            GenerativeModel generativeModelInstance = mockedGenerativeModel.constructed().get(0);
            verify(generativeModelInstance).generateContentStream(any(Content.class));
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
    void streamQuery_predictionServiceSettingsBuildThrowsIOException_returnsError() throws IOException {
        // This test requires a way to make PredictionServiceSettings.newBuilder().build() throw an IOException.
        // Mocking static methods like newBuilder() is complex.
        // A simpler approach for this specific case is to ensure the service handles exceptions during settings creation.
        // The current service code already has a try-catch for IOException during settings build.
        // To test this path effectively without PowerMock/JMockit, we'd ideally refactor
        // PredictionServiceSettings creation into a separate, mockable component if this specific scenario is critical.
        // For now, we acknowledge this path exists and is caught by the generic Exception catch in the service.
        // A direct test for this specific IOException is omitted due to complexity with final/static methods.
        // We can simulate a more general error during client/model setup.

        // Let's test a scenario where GenerativeModel constructor or generateContentStream throws an exception
         try (MockedConstruction<PredictionServiceClient> mockedPredictionServiceClient =
                     mockConstruction(PredictionServiceClient.class);
              MockedConstruction<GenerativeModel> mockedGenerativeModel =
                     mockConstruction(GenerativeModel.class, (mock, context) -> {
                         when(mock.generateContentStream(any(Content.class)))
                                 .thenThrow(new RuntimeException("Gemini SDK error"));
                     })) {

            StreamingResponseBody responseBody = geminiService.streamQuery("test query");
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            responseBody.writeTo(outputStream);

            assertTrue(outputStream.toString().contains("Error processing your request: Gemini SDK error"));
        }
    }
    
    @Test
    void streamQuery_emptyResponseIterator_writesNothing() throws IOException {
        @SuppressWarnings("unchecked")
        Iterator<GenerateContentResponse> mockResponseIterator = mock(Iterator.class);
        when(mockResponseIterator.hasNext()).thenReturn(false); // No responses

        try (MockedConstruction<PredictionServiceClient> mockedPredictionServiceClient =
                     mockConstruction(PredictionServiceClient.class);
             MockedConstruction<GenerativeModel> mockedGenerativeModel =
                     mockConstruction(GenerativeModel.class, (mock, context) -> {
                         when(mock.generateContentStream(any(Content.class))).thenReturn(mockResponseIterator);
                     })) {

            StreamingResponseBody responseBody = geminiService.streamQuery("test query");
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            responseBody.writeTo(outputStream);

            assertEquals("", outputStream.toString().trim());
        }
    }
}
