#!/usr/bin/env python3
"""Bounded loopback ingress for the real, persistent whisper.cpp server.

Python standard library only. No audio is saved and no credentials are read.
"""

import argparse
from email import policy
from email.parser import BytesParser
import http.client
from http.server import BaseHTTPRequestHandler, HTTPServer
import io
import json
import re
import signal
from socketserver import ThreadingMixIn
import subprocess
import sys
import threading
import uuid
import wave


MAX_AUDIO_BYTES = 4 * 1024 * 1024
MAX_REQUEST_BYTES = MAX_AUDIO_BYTES + 16 * 1024
INFERENCE_TIMEOUT_SECONDS = 40
MAX_RESPONSE_BYTES = 64 * 1024
DEFAULT_PROMPT = "UML, clase, atributo, asociacion, agregacion, composicion, generalizacion, multiplicidad"


class InputError(Exception):
    def __init__(self, status, message):
        self.status = status
        super().__init__(message)


def parse_audio(body, content_type):
    message = BytesParser(policy=policy.default).parsebytes(
        b"Content-Type: " + content_type.encode("ascii") + b"\r\nMIME-Version: 1.0\r\n\r\n" + body
    )
    boundary = message.get_boundary() or ""
    if (message.get_content_type() != "multipart/form-data" or not message.is_multipart()
            or message.defects or not re.fullmatch(r"[A-Za-z0-9'()+_,./:=?-]{1,200}", boundary)):
        raise InputError(400, "Se requiere multipart/form-data valido.")
    allowed = {"file", "language", "response_format", "temperature", "temperature_inc", "prompt"}
    fields = {}
    for part in message.iter_parts():
        name = part.get_param("name", header="content-disposition")
        if (name not in allowed or name in fields or part.is_multipart() or part.defects
                or part.get_content_disposition() != "form-data" or part.get("Content-Transfer-Encoding")):
            raise InputError(400, "Campos de audio invalidos o duplicados.")
        payload = part.get_payload(decode=True)
        if not isinstance(payload, bytes):
            raise InputError(400, "Campo de audio invalido.")
        if name == "file":
            fields[name] = payload
        else:
            if len(payload) > 1024:
                raise InputError(400, "Parametro de audio demasiado largo.")
            fields[name] = payload.decode("utf-8")
    if "file" not in fields:
        raise InputError(400, "Falta el archivo WAV.")
    if fields.get("language", "es") != "es" or fields.get("response_format", "json") != "json":
        raise InputError(400, "Se requiere idioma es y formato json.")
    audio = fields["file"]
    if len(audio) > MAX_AUDIO_BYTES:
        raise InputError(413, "El WAV supera 4 MiB.")
    if (len(audio) < 44 or audio[:4] != b"RIFF" or audio[8:12] != b"WAVE"
            or int.from_bytes(audio[4:8], "little") != len(audio) - 8):
        raise InputError(400, "WAV PCM incompleto o invalido.")
    try:
        with wave.open(io.BytesIO(audio), "rb") as wav:
            frames = wav.getnframes()
            if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getcomptype()) != (1, 2, 16000, "NONE"):
                raise InputError(400, "Se requiere WAV PCM16 mono a 16000 Hz.")
            if frames < 4000 or len(wav.readframes(frames)) != frames * 2:
                raise InputError(400, "WAV vacio, demasiado corto o truncado.")
    except (wave.Error, EOFError) as error:
        raise InputError(400, "WAV PCM invalido.") from error
    return audio, fields.get("prompt", DEFAULT_PROMPT)


def upstream_body(audio, prompt):
    boundary = "ClassForgeWhisper" + uuid.uuid4().hex
    body = bytearray()
    # Rebuild the request: client filenames and extra inference options never reach C++.
    for name, value in {"language": "es", "response_format": "json", "temperature": "0.0",
                        "temperature_inc": "0.0", "prompt": prompt}.items():
        body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode("utf-8"))
    body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="classforge-voice.wav"\r\nContent-Type: audio/wav\r\n\r\n'.encode())
    body.extend(audio)
    body.extend(f"\r\n--{boundary}--\r\n".encode())
    return bytes(body), f"multipart/form-data; boundary={boundary}"


class WhisperRuntime:
    def __init__(self, binary, model, port=8094):
        self.port = port
        self.process = subprocess.Popen(
            [binary, "-m", model, "-l", "es", "-t", "2", "-p", "1", "-bo", "1", "-bs", "1",
             "-ng", "-nfa", "-nt", "-mc", "0", "-nf", "--host", "127.0.0.1", "--port", str(port)],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            env={"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8", "HOME": "/nonexistent",
                 "OMP_NUM_THREADS": "2", "OPENBLAS_NUM_THREADS": "1"},
        )

    def ready(self):
        if self.process.poll() is not None:
            return False
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=1)
        try:
            connection.request("GET", "/health")
            response = connection.getresponse()
            body = response.read(4097)
            return response.status == 200 and len(body) <= 4096 and json.loads(body).get("status") == "ok"
        except (OSError, ValueError, AttributeError, http.client.HTTPException):
            return False
        finally:
            connection.close()

    def transcribe(self, audio, prompt):
        body, content_type = upstream_body(audio, prompt)
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=INFERENCE_TIMEOUT_SECONDS)
        try:
            connection.request("POST", "/inference", body, {"Content-Type": content_type})
            response = connection.getresponse()
            result = response.read(MAX_RESPONSE_BYTES + 1)
            if response.status != 200 or len(result) > MAX_RESPONSE_BYTES:
                raise RuntimeError("Respuesta de transcripcion invalida.")
            result = json.loads(result)
            if not isinstance(result, dict) or not isinstance(result.get("text"), str):
                raise RuntimeError("Respuesta de transcripcion invalida.")
            return result["text"]
        finally:
            connection.close()

    def abort(self):
        if self.process.poll() is None:
            # A timed out inference must not continue occupying the CPU or queue new work.
            self.process.kill()
        self.process.wait(timeout=3)


class LocalServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 8

    def __init__(self, address, runtime):
        self.runtime = runtime
        self.inference_slot = threading.BoundedSemaphore(1)
        self.connection_slots = threading.BoundedSemaphore(4)
        super().__init__(address, Handler)

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(3)
        return connection, address

    def process_request(self, request, client_address):
        if not self.connection_slots.acquire(blocking=False):
            try:
                request.sendall(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            except OSError:
                pass
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.connection_slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.connection_slots.release()


class Handler(BaseHTTPRequestHandler):
    server_version = "whisper.cpp/ClassForge"
    sys_version = ""

    def log_message(self, format, *args):
        pass  # Never put filenames, prompts, transcripts or request bodies in the journal.

    def reply(self, status, body, retry_after=None):
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        if retry_after:
            self.send_header("Retry-After", str(retry_after))
        self.end_headers()
        self.wfile.write(payload)
        self.close_connection = True

    def do_GET(self):
        if self.path == "/health":
            ready = self.server.runtime.ready()
            self.reply(200 if ready else 503, {"status": "ok" if ready else "loading model"})
        elif self.path == "/":
            self.reply(200, {"runtime": "whisper.cpp", "language": "es"})
        else:
            self.reply(404, {"error": "Ruta no disponible."})

    def do_POST(self):
        if self.path != "/inference":
            self.reply(404, {"error": "Ruta no disponible."})
            return
        acquired = False
        try:
            lengths = self.headers.get_all("Content-Length", [])
            if self.headers.get("Transfer-Encoding") or len(lengths) != 1 or not lengths[0].isdigit():
                raise InputError(400, "Se requiere un Content-Length valido.")
            length = int(lengths[0])
            if length > MAX_REQUEST_BYTES:
                raise InputError(413, "La solicitud de audio supera el limite.")
            if length <= 0:
                raise InputError(400, "Falta el archivo WAV.")
            content_type = self.headers.get("Content-Type", "")
            if len(content_type) > 512 or "\r" in content_type or "\n" in content_type:
                raise InputError(400, "Content-Type invalido.")
            acquired = self.server.inference_slot.acquire(blocking=False)
            if not acquired:
                self.reply(429, {"error": "Transcripcion ocupada. Reintenta en un momento."}, retry_after=2)
                return
            if not self.server.runtime.ready():
                self.reply(503, {"error": "El modelo de voz no esta disponible."}, retry_after=2)
                return
            body = self.rfile.read(length)
            if len(body) != length:
                raise InputError(400, "Audio incompleto.")
            audio, prompt = parse_audio(body, content_type)
            try:
                transcript = self.server.runtime.transcribe(audio, prompt)
            except TimeoutError:
                self.reply(504, {"error": "La transcripcion excedio el plazo."})
                self.server.runtime.abort()
                return
            self.reply(200, {"text": transcript})
        except InputError as error:
            self.reply(error.status, {"error": str(error)})
        except TimeoutError:
            self.reply(408, {"error": "La carga de audio excedio el plazo."})
        except (OSError, ValueError, RuntimeError, http.client.HTTPException):
            self.reply(502, {"error": "No se pudo completar la transcripcion local."})
        finally:
            if acquired:
                self.server.inference_slot.release()


def create_server(runtime, port=8093):
    return LocalServer(("127.0.0.1", port), runtime)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", default="/opt/classforge/whisper/whisper-server")
    parser.add_argument("--model", default="/opt/classforge/data/whisper/ggml-small-q8_0.bin")
    parser.add_argument("--port", type=int, default=8093)
    parser.add_argument("--upstream-port", type=int, default=8094)
    args = parser.parse_args()
    stop = threading.Event()
    failed = threading.Event()
    runtime = WhisperRuntime(args.binary, args.model, args.upstream_port)
    try:
        server = create_server(runtime, args.port)
        def monitor():
            while not stop.wait(0.2):
                if runtime.process.poll() is not None:
                    failed.set()
                    break
            server.shutdown()
        for sig in (signal.SIGTERM, signal.SIGINT):
            signal.signal(sig, lambda *_: stop.set())
        threading.Thread(target=monitor, daemon=True).start()
        try:
            server.serve_forever(poll_interval=0.2)
        finally:
            stop.set()
            server.server_close()
    finally:
        runtime.abort()
    if failed.is_set():
        print("El runtime whisper.cpp termino; systemd lo reiniciara.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
