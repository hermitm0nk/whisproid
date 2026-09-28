#!/usr/bin/env bash
set -euo pipefail

: "${GOOGLE_AI_STUDIO_KEY:?GOOGLE_AI_STUDIO_KEY secret is required}"
: "${RUNNER_TEMP:?RUNNER_TEMP is required}"

PROTO_OUT="$RUNNER_TEMP/emulator-grpc-proto"
mkdir -p "$PROTO_OUT"
PROTO_INCLUDE="$(python -c 'import pathlib, grpc_tools; print(pathlib.Path(grpc_tools.__file__).parent / "_proto")')"
python -m grpc_tools.protoc \
  -I scripts/proto -I "$PROTO_INCLUDE" \
  --python_out="$PROTO_OUT" --grpc_python_out="$PROTO_OUT" \
  scripts/proto/emulator_controller.proto

export PYTHONPATH="$PROTO_OUT${PYTHONPATH:+:$PYTHONPATH}"
python scripts/emulator_gemini_audio_e2e.py "$RUNNER_TEMP/whisproid-e2e.wav"
