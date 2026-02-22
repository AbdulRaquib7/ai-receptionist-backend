package com.ai.receptionist.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.HttpClientErrorException;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Speech-to-Text using OpenAI Whisper.
 * Converts Twilio μ-law audio → PCM WAV before transcription.
 */
@Service
public class SttService {

    private static final Logger log = LoggerFactory.getLogger(SttService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${openai.api-key:${OPENAI_API_KEY:}}")
    private String openAiApiKey;

    public SttService(RestTemplateBuilder builder) {
        this.restTemplate = builder.build();
    }

    public String transcribe(byte[] mulawAudio) {

        if (StringUtils.isBlank(openAiApiKey)) {
            log.error("OPENAI_API_KEY is not set");
            return "";
        }

        try {
            byte[] wavAudio = convertMulawToWav(mulawAudio);

            String url = "https://api.openai.com/v1/audio/transcriptions";
            
            log.info("🎧 WAV bytes={} (~{} ms)",
                    wavAudio.length,
                    (wavAudio.length - 44) / 16); // approx ms @ 8kHz PCM

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(openAiApiKey);
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("model", "whisper-1");
            form.add("response_format", "json");

            form.add("file", new ByteArrayResource(wavAudio) {
                @Override
                public String getFilename() {
                    return "audio.wav";
                }

                @Override
                public long contentLength() {
                    return wavAudio.length;
                }
            });

            HttpEntity<MultiValueMap<String, Object>> request =
                    new HttpEntity<>(form, headers);

            ResponseEntity<String> response =
                    restTemplate.postForEntity(url, request, String.class);

            JsonNode node = objectMapper.readTree(response.getBody());
            return node.path("text").asText("").trim();

        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("OpenAI API returned 401 Unauthorized. Check that openai.api-key in application.properties (or OPENAI_API_KEY env) is set and valid. Keys: https://platform.openai.com/api-keys");
            return "";
        } catch (Exception ex) {
            log.error("Failed to transcribe audio", ex);
            return "";
        }
    }

    /**
     * Convert Twilio μ-law (8kHz) to PCM WAV for Whisper.
     */
    private byte[] convertMulawToWav(byte[] mulaw) throws Exception {

        // μ-law: 8kHz, mono, 8-bit, 1 byte per frame
        AudioFormat mulawFormat = new AudioFormat(
                AudioFormat.Encoding.ULAW,
                8000f,
                8,
                1,
                1,          // frame size = 1 byte
                8000f,
                false
        );

        // PCM: 16-bit signed
        AudioFormat pcmFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                8000f,
                16,
                1,
                2,          // frame size = 2 bytes
                8000f,
                false
        );

        // ✅ CORRECT frame length (NOT mulaw.length blindly)
        long frameLength = mulaw.length; // 1 byte per frame for μ-law

        try (
                ByteArrayInputStream bais = new ByteArrayInputStream(mulaw);
                AudioInputStream mulawStream =
                        new AudioInputStream(bais, mulawFormat, frameLength);
                AudioInputStream pcmStream =
                        AudioSystem.getAudioInputStream(pcmFormat, mulawStream);
                ByteArrayOutputStream baos = new ByteArrayOutputStream()
        ) {
            AudioSystem.write(pcmStream, AudioFileFormat.Type.WAVE, baos);
            return baos.toByteArray();
        }
    }

}
