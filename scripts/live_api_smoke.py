"""Secret-backed Gemini Live protocol smoke test with generated, nonprivate speech."""
import asyncio
import json
import os
from pathlib import Path
from urllib.parse import urlencode

import websockets


async def run():
    key = os.environ.get("GOOGLE_AI_STUDIO_KEY", "")
    if not key:
        raise RuntimeError("GOOGLE_AI_STUDIO_KEY is missing")
    pcm = Path("speech.pcm").read_bytes()
    assert pcm and len(pcm) % 2 == 0
    url = ("wss://generativelanguage.googleapis.com/ws/"
           "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?"
           + urlencode({"key": key}))
    setup = {"setup": {
        "model": "models/gemini-3.5-transcribe-live",
        "generationConfig": {"responseModalities": ["TEXT"]},
        "realtimeInputConfig": {"automaticActivityDetection": {"disabled": True}},
        "inputAudioTranscription": {"mode": "SMART"},
    }}
    frame_types = set()

    async def receive_json(ws, seconds):
        frame = await asyncio.wait_for(ws.recv(), seconds)
        frame_types.add("binary" if isinstance(frame, bytes) else "text")
        return json.loads(frame)

    async with websockets.connect(url, max_size=None) as ws:
        await ws.send(json.dumps(setup))
        while True:
            response = await receive_json(ws, 20)
            if "setupComplete" in response:
                break
        await ws.send(json.dumps({"realtimeInput": {"activityStart": {}}}))
        import base64
        for offset in range(0, len(pcm), 3200):
            chunk = pcm[offset:offset + 3200]
            await ws.send(json.dumps({"realtimeInput": {"audio": {
                "data": base64.b64encode(chunk).decode("ascii"),
                "mimeType": "audio/pcm;rate=16000",
            }}}))
            await asyncio.sleep(len(chunk) / 32000)
        pre_end = []
        # Hold the button for a quiet interval to see whether the model finalizes
        # a segment before the explicit release signal.
        for _ in range(12):
            await asyncio.sleep(.25)
            while True:
                try:
                    earlier = await receive_json(ws, .01)
                except asyncio.TimeoutError:
                    break
                text = ((earlier.get("serverContent") or {}).get("inputTranscription") or {}).get("text", "").strip()
                if text:
                    pre_end.append(text)
        await ws.send(json.dumps({"realtimeInput": {"activityEnd": {}}}))
        finalized = []
        turn_complete = False
        deadline = asyncio.get_running_loop().time() + 30
        while asyncio.get_running_loop().time() < deadline:
            try:
                response = await receive_json(ws, 5)
            except asyncio.TimeoutError:
                if finalized:
                    break
                continue
            content = response.get("serverContent") or {}
            text = (content.get("inputTranscription") or {}).get("text", "").strip()
            if text:
                finalized.append(text)
            if content.get("turnComplete"):
                turn_complete = True
            if turn_complete and finalized:
                break
    combined = " ".join(pre_end + finalized).lower()
    if "sky is blue" not in combined:
        raise AssertionError("Finalized transcription did not contain the expected speech")
    print(f"Gemini Live finalized expected speech; preEnd={len(pre_end)}, postEnd={len(finalized)}, "
          f"turnComplete={turn_complete}, frameTypes={','.join(sorted(frame_types))}")


if __name__ == "__main__":
    asyncio.run(run())
