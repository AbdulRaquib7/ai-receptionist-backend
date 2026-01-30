package com.ai.receptionist.simulator;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.Base64;

public class LocalMediaStreamSimulator {

    private static final String WS_URL = "ws://127.0.0.1:8080/media-stream";
    private static final String STREAM_SID = "SIM_STREAM_001";

    // Twilio: 20ms @ 8kHz μ-law
    private static final int FRAME_SIZE = 320;

    public static void main(String[] args) throws Exception {

        File audioFile = new File("src/test/resources/test.ulaw");
        if (!audioFile.exists()) {
            throw new RuntimeException(
                "❌ test.ulaw not found at src/test/resources/test.ulaw"
            );
        }

        final byte[] audio = Files.readAllBytes(audioFile.toPath());
        System.out.println("🎧 Loaded audio bytes = " + audio.length);

        WebSocketClient client = new WebSocketClient(new URI(WS_URL)) {

            @Override
            public void onOpen(ServerHandshake handshake) {
                System.out.println("✅ CONNECTED TO WS");

                // 1️⃣ START
                send("{"
                        + "\"event\":\"start\","
                        + "\"streamSid\":\"" + STREAM_SID + "\","
                        + "\"callSid\":\"SIM_CALL_001\""
                        + "}");

                try {
                    int offset = 0;
                    int frameCount = 0;

                    while (offset + FRAME_SIZE <= audio.length) {

                        byte[] frame = new byte[FRAME_SIZE];
                        System.arraycopy(audio, offset, frame, 0, FRAME_SIZE);
                        offset += FRAME_SIZE;
                        frameCount++;

                        String payload =
                                Base64.getEncoder().encodeToString(frame);

                        send("{"
                                + "\"event\":\"media\","
                                + "\"streamSid\":\"" + STREAM_SID + "\","
                                + "\"media\":{"
                                + "\"payload\":\"" + payload + "\""
                                + "}"
                                + "}");

                        // simulate Twilio timing
                        Thread.sleep(20);
                    }

                    System.out.println("📤 Sent frames = " + frameCount);

                    // Small pause before STOP (important)
                    Thread.sleep(200);

                    // 3️⃣ STOP
                    send("{"
                            + "\"event\":\"stop\","
                            + "\"streamSid\":\"" + STREAM_SID + "\""
                            + "}");

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onMessage(String message) {
                System.out.println("⬅ SERVER → " + message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                System.out.println("❌ WS CLOSED: " + reason);
            }

            @Override
            public void onError(Exception ex) {
                ex.printStackTrace();
            }
        };

        client.connectBlocking();
    }
}
