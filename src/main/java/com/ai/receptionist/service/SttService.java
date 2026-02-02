package com.ai.receptionist.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;

@Service
public class SttService {

    private static final Logger log = LoggerFactory.getLogger(SttService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${openai.api-key:${OPENAI_API_KEY:}}")
    private String openAiApiKey;

    public SttService(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();
    }

    public String transcribe(byte[] mulawAudio) {

        if (StringUtils.isBlank(openAiApiKey)) {
            log.error("OPENAI_API_KEY not configured");
            return "";
        }

        try {
            byte[] wavAudio = convertMulawToWav(mulawAudio);

            log.info("🎧 WAV bytes={} (~{} ms)",
                    wavAudio.length,
                    (wavAudio.length - 44) / 16);

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(openAiApiKey);
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("model", "whisper-1");
            form.add("file", new ByteArrayResource(wavAudio) {
                @Override public String getFilename() { return "audio.wav"; }
                @Override public long contentLength() { return wavAudio.length; }
            });

            HttpEntity<MultiValueMap<String, Object>> request =
                    new HttpEntity<>(form, headers);

            ResponseEntity<String> response =
                    restTemplate.postForEntity(
                            "https://api.openai.com/v1/audio/transcriptions",
                            request,
                            String.class
                    );

            JsonNode node = objectMapper.readTree(response.getBody());
            return node.path("text").asText("").trim();

        } catch (Exception e) {
            log.error("Failed to transcribe audio", e);
            return "";
        }
    }

    private byte[] convertMulawToWav(byte[] mulaw) throws Exception {

        AudioFormat mulawFormat = new AudioFormat(
                AudioFormat.Encoding.ULAW,
                8000f, 8, 1, 1, 8000f, false
        );

        AudioFormat pcmFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                8000f, 16, 1, 2, 8000f, false
        );

        try (
                ByteArrayInputStream bais = new ByteArrayInputStream(mulaw);
                AudioInputStream mulawStream =
                        new AudioInputStream(bais, mulawFormat, mulaw.length);
                AudioInputStream pcmStream =
                        AudioSystem.getAudioInputStream(pcmFormat, mulawStream);
                ByteArrayOutputStream baos = new ByteArrayOutputStream()
        ) {
            AudioSystem.write(pcmStream, AudioFileFormat.Type.WAVE, baos);
            return baos.toByteArray();
        }
    }
}
