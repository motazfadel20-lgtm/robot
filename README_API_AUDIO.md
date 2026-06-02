This document lists the new audio-related endpoints added for initial implementation.

Endpoints:

- `api/joe/upload_audio.php` (POST JSON)
  - body: `{ file_base64, file_name, mime_type }`
  - saves audio to `storage/uploads/` and returns `path`, `file_name`, `size`, `url`.

- `api/joe/analyze_call.php` (POST JSON)
  - body: `{ file_path }` or `{ file_base64, file_name }`
  - if `OPENAI_API_KEY` configured, attempts to call OpenAI audio transcription endpoint, returns `transcript` and naive `insights`.
  - otherwise returns placeholder note and no transcript.

DB Migration:
- `api/joe/migrations/20260602_create_pharmacies.sql` creates table `pharmacies` to store pharmacy names and prices.

Next steps suggestions:
- Add server-side insertion into `pharmacies` table and API CRUD endpoints.
- Add Room entities and Android UI for pharmacies/prices.
- Integrate `analyze_call.php` results into upload history and call-insights storage.
