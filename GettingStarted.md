# Getting Started
To run the Impekable Front Desk Spring Boot application, follow these steps:

## Prerequisites
* Java 17 or higher: The project is configured for Java 17.
* Maven: Used for building and running the application.
* PostgreSQL: A local instance running on port 5432.
* Ngrok (Recommended): Since the app uses Twilio Webhooks and Media Streams, you'll need to expose your local server to the internet.

## Database Setup
Ensure you have a PostgreSQL database named ai-receptionist with the following credentials (default from application.properties):
* URL: jdbc:postgresql://localhost:5432/ai-receptionist
* Username: postgres
* Password: postgres

## Configuration
Update src/main/resources/application.properties with your specific API keys and connection URLs:
* 
* OpenAI: openai.api-key (for transcription and intent extraction)
* ElevenLabs: elevenlabs.api-key (for high-quality TTS)
* Twilio: twilio.account-sid, twilio.auth-token, and twilio.phone-number.
* Ngrok URLs: For testing from your local machine, update twilio.media-stream-url (starting with wss://) and twilio.base-url (starting with https://) with your current ngrok
  public URL.

## Running the Application
Open your terminal in the project root and run:

mvn spring-boot:run -Dspring-boot.run.profiles=local

## Initialization
* Data Seeding: On the first run, DataSeeder.java will automatically populate the database with sample doctors (Dr. Ahmed, Dr. John, Dr.
  Alan) and available appointment slots for the next 7 days.
* Twilio Integration: Once the app is running and exposed via ngrok (if running locally), configure your Twilio phone number's "A Call Comes In" webhook to
  point to your ngrok URL: https://<your-ngrok-id>.ngrok-free.dev/api/voice/incoming. Otherwise, point it to the Google Cloud Run application URL.