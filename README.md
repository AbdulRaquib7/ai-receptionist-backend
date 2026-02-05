# AI Receptionist Backend

Voice AI receptionist using Twilio Media Streams, OpenAI Whisper (STT), and OpenAI Chat (LLM). Supports doctor appointment booking, cancellation, and rescheduling via phone calls.

## Requirements

- Java 17+
- Maven
- Twilio account
- OpenAI API key
- (Optional) PostgreSQL for production; H2 used by default for local dev

## Quick Start

```bash
# Run with default config (H2 in-memory DB)
mvn spring-boot:run

# Run with your OpenAI key via env (recommended - avoids token overwrite)
OPENAI_API_KEY=sk-your-key mvn spring-boot:run

# Run with PostgreSQL
mvn spring-boot:run -Dspring.profiles.active=prod
```

## Configuration

### application.properties

| Property | Description | Default |
|----------|-------------|---------|
| `openai.api-key` | OpenAI API key for Whisper + Chat | (required) |
| `OPENAI_API_KEY` | Env var that overrides `openai.api-key` | - |
| `twilio.account-sid` | Twilio Account SID | - |
| `twilio.auth-token` | Twilio Auth Token | - |
| `twilio.media-stream-url` | WebSocket URL for media stream (ngrok) | - |
| `twilio.base-url` | Base URL for Twilio webhooks | - |
| `server.port` | HTTP port | 8080 |

### Database

- **Default (H2)**: In-memory, no setup. Data resets on restart.
- **PostgreSQL**: Set `-Dspring.profiles.active=prod`. Configure in `application-prod.properties`.

### OpenAI Key (avoid 401 / token overwrite)

1. **Env var** (recommended): `OPENAI_API_KEY=sk-xxx mvn spring-boot:run`
2. **Local file**: Copy `application-local.properties.example` to `application-local.properties`, add your key, run with `-Dspring.profiles.active=local`
3. **In file**: Set `openai.api-key` in `application.properties` (avoid committing to public repos)

## Twilio Setup

1. Configure your Twilio phone number webhook to point to your ngrok URL:
   - Voice: `POST https://your-ngrok-url/twilio/voice/inbound`
2. Ensure `twilio.media-stream-url` and `twilio.base-url` match your ngrok URL.
3. Run ngrok: `ngrok http 8080`

## Features

- **Booking**: Book doctor appointments by voice (doctor selection, slot, name, phone)
- **Cancel**: Cancel existing appointment with confirmation
- **Reschedule**: Change appointment to a new slot
- **Caller ID**: Captures caller phone from Twilio for faster booking
- **Slot safety**: Pessimistic locking prevents double-booking

## Project Structure

```
src/main/java/com/ai/receptionist/
├── auth/ReceptionistApplication.java
├── config/DataInitializer.java, WebSocketConfig.java
├── controller/VoiceController.java
├── entity/          # JPA entities
├── repository/      # Spring Data JPA repos
├── service/         # BookingFlowService, SlotService, LlmService, SttService, etc.
└── websocket/MediaStreamHandler.java
```

## Build

```bash
mvn clean compile
mvn spring-boot:run
```
