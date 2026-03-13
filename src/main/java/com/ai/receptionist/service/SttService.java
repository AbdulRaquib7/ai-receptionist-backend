package com.ai.receptionist.service;

import com.ai.receptionist.exception.SttException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

@Service
public class SttService {

    private static final Logger log = LoggerFactory.getLogger(SttService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${openai.api-key}")
    private String openAiApiKey;

    public SttService(@Qualifier("sttRestTemplate") RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Retryable(
            retryFor = {ResourceAccessException.class, HttpServerErrorException.class},
            noRetryFor = {HttpClientErrorException.class},
            maxAttempts = 2,
            backoff = @Backoff(delay = 500)
    )
    public String transcribe(byte[] mulawAudio) {

        if (StringUtils.isBlank(openAiApiKey)) {
            throw new SttException("OPENAI_API_KEY is not set", null);
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
            throw new SttException("STT authentication failed — check OPENAI_API_KEY", e);
        } catch (ResourceAccessException e) {
            throw new SttException("STT service unreachable", e);
        } catch (HttpServerErrorException e) {
            throw new SttException("STT server error: " + e.getStatusCode(), e);
        } catch (Exception ex) {
            throw new SttException("STT transcription failed", ex);
        }
    }

    private byte[] convertMulawToWav(byte[] mulaw) throws Exception {

        AudioFormat mulawFormat = new AudioFormat(
                AudioFormat.Encoding.ULAW,
                8000f,
                8,
                1,
                1,          // frame size = 1 byte
                8000f,
                false
        );

        AudioFormat pcmFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                8000f,
                16,
                1,
                2,          // frame size = 2 bytes
                8000f,
                false
        );

        long frameLength = mulaw.length;

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
