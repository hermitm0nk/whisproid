#!/usr/bin/env bash
set -euo pipefail

: "${GOOGLE_AI_STUDIO_KEY:?GOOGLE_AI_STUDIO_KEY secret is required}"
test -s app/src/debug/assets/synthetic.pcm
python scripts/emulator_gemini_audio_e2e.py
