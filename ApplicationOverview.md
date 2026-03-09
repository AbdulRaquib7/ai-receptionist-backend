# Impekable Front Desk AI Receptionist - Appplication Overview

## Overview
Impekable Front Desk is an AI-powered receptionist system designed to handle medical appointment bookings and general inquiries via voice calls. It integrates real-time voice streaming with Large Language Models (LLMs) to provide a natural, conversational experience for patients.

## Architecture & Tech Stack
- **Framework:** Spring Boot (Java)
- **Telephony:** Twilio (Voice Webhooks + Media Streams)
- **AI/ML:** 
    - **STT (Speech-to-Text):** OpenAI Whisper API
    - **LLM & Intent Classification:** OpenAI GPT-4o-mini
    - **TTS (Text-to-Speech):** Amazon Polly (via Twilio `<Say>`)
- **Database:** PostgreSQL (with Spring Data JPA)
- **Real-time Communication:** WebSockets for receiving raw audio streams from Twilio.

## Core Workflows

### 1. Call Handling & Voice Processing
- **Inbound Calls:** Twilio sends a webhook to `VoiceController`. The system responds with TwiML instructions to `<Connect>` to a `<Stream>`, which directs raw mu-law audio to the application's WebSocket endpoint.
- **Audio Streaming:** `MediaStreamHandler` manages the WebSocket connection, buffering incoming audio chunks. It uses silence detection to identify when a user has finished speaking (an "utterance").

### 2. Conversational Logic (STT -> LLM -> TTS)
- **Transcription:** Once an utterance is detected, `SttService` converts the mu-law audio buffer to WAV format and sends it to OpenAI Whisper for transcription.
- **Intent Extraction:** `BookingFlowService` uses GPT-4o-mini to parse the transcribed text into a structured intent (e.g., BOOK, CANCEL, QUERY) and extracts relevant entities like doctor names, dates, and times.
- **Response Generation:**
    - If a booking is in progress, the system follows a state-machine logic to guide the user through the process.
    - If it's a general inquiry, `LlmService` generates a natural language response, enriched with current database context (e.g., available doctors and slots).
- **Audio Output:** The generated text is sent back to the caller using `TwilioService`. This triggers a TwiML update on the active call, typically using `<Say>` (Amazon Polly) to convert the text back to speech.

### 3. Appointment Booking Flow
The booking process is a stateful, multi-turn conversation managed by `BookingFlowService`. It tracks the current progress (e.g., selecting a doctor, finding a slot, collecting patient info) using `PendingStateDto`. The flow typically includes:
1. Identifying the required doctor or specialization.
2. Checking real-time availability in the database.
3. Suggesting and confirming a specific time slot.
4. Collecting patient details (name, phone).
5. Final confirmation and database persistence.

## Data Model
- **Doctor:** Profiles of medical staff, including their specializations.
- **Patient:** Records of callers and their contact information.
- **AppointmentSlot:** Individual time blocks that are either `AVAILABLE` or `BOOKED`.
- **Appointment:** The final record linking a Patient, Doctor, and Slot.
- **ConversationHistory:** Logs of all interactions for auditing and context-aware responses.

## Key Components
- `VoiceController`: Entry point for Twilio webhooks.
- `MediaStreamHandler`: Manages real-time audio WebSockets and coordinates the processing pipeline.
- `SttService`: Handles audio conversion and Whisper API interaction.
- `LlmService`: Manages general conversational logic using GPT models.
- `BookingFlowService`: Orchestrates the complex, stateful booking logic.
- `TwilioService`: Interface for making REST calls to the Twilio API (e.g., updating live calls).
