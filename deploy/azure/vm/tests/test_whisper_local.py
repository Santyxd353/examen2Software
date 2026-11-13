"""Run with python -B -m unittest discover -s deploy/azure/vm/tests -v."""

import concurrent.futures
from email import policy
from email.parser import BytesParser
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
from pathlib import Path
import threading
import time
import unittest
import wave


MODULE_PATH = Path(__file__).resolve().parents[1] / "whisper-local.py"
SPEC = importlib.util.spec_from_file_location("whisper_local", MODULE_PATH)
whisper = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(whisper)


def wav_bytes(rate=16000, channels=1, width=2):
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(channels)
        audio.setsampwidth(width)
        audio.setframerate(rate)
        audio.writeframes(b"\0" * (rate * channels * width // 4))
    return output.getvalue()


def multipart(audio=None, fields=None):
    boundary = "ClassForgeWhisperProtocolTest"
    body = bytearray()
    for name, value in (fields or {}).items():
        body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    if audio is not None:
        body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="voice.wav"\r\nContent-Type: audio/wav\r\n\r\n'.encode())
        body.extend(audio)
        body.extend(b"\r\n")
    body.extend(f"--{boundary}--\r\n".encode())
    return bytes(body), f"multipart/form-data; boundary={boundary}"


class Runtime:
    def __init__(self):
        self.available = True
        self.calls = []
        self.entered = threading.Event()
        self.release = threading.Event()
        self.block = False
        self.timeout = False
        self.aborted = False

    def ready(self):
        return self.available and not self.aborted

    def transcribe(self, audio, prompt):
        self.calls.append((audio, prompt))
        self.entered.set()
        if self.block:
            self.release.wait(3)
        if self.timeout:
            raise TimeoutError("test timeout")
        return "Agrega una clase Cliente."

    def abort(self):
        self.aborted = True


class ProtocolTests(unittest.TestCase):
    def setUp(self):
        self.runtime = Runtime()
        self.server = whisper.create_server(self.runtime, port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.runtime.release.set()
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)

    def request(self, method="GET", path="/health", body=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        try:
            connection.request(method, path, body, headers or {})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), json.loads(response.read())
        finally:
            connection.close()

    def inference(self, audio=None, fields=None):
        body, content_type = multipart(wav_bytes() if audio is None else audio, fields)
        return self.request("POST", "/inference", body, {"Content-Type": content_type})

    def test_health_reflects_actual_runtime_and_identifies_whisper(self):
        status, headers, body = self.request()
        self.assertEqual((200, "ok"), (status, body["status"]))
        self.assertIn("whisper.cpp", headers["Server"])
        self.runtime.available = False
        self.assertEqual(503, self.request()[0])

    def test_accepts_existing_java_multipart_contract(self):
        audio = wav_bytes()
        fields = {"language": "es", "response_format": "json", "temperature": "0.0", "temperature_inc": "0.2", "prompt": "UML, clase, atributo"}
        status, _, body = self.inference(audio, fields)
        self.assertEqual((200, "Agrega una clase Cliente."), (status, body["text"]))
        self.assertEqual([(audio, fields["prompt"])], self.runtime.calls)

    def test_health_stays_responsive_and_second_inference_is_rejected(self):
        self.runtime.block = True
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            first = executor.submit(self.inference)
            self.assertTrue(self.runtime.entered.wait(2))
            self.assertEqual(200, self.request()[0])
            status, headers, _ = self.inference()
            self.assertEqual(429, status)
            self.assertEqual("2", headers["Retry-After"])
            self.runtime.release.set()
            self.assertEqual(200, first.result()[0])

    def test_rejects_oversized_envelope_before_reading_body(self):
        self.assertEqual(413, self.request("POST", "/inference", b"", {"Content-Type": "multipart/form-data; boundary=x", "Content-Length": str(whisper.MAX_REQUEST_BYTES + 1)})[0])

    def test_rejects_audio_over_four_mib(self):
        self.assertEqual(413, self.inference(b"x" * (4 * 1024 * 1024 + 1))[0])
        self.assertEqual([], self.runtime.calls)

    def test_accepts_wav_at_the_exact_four_mib_limit(self):
        output = io.BytesIO()
        with wave.open(output, "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(16000)
            audio.writeframes(b"\0" * (whisper.MAX_AUDIO_BYTES - 44))
        self.assertEqual(whisper.MAX_AUDIO_BYTES, len(output.getvalue()))
        self.assertEqual(200, self.inference(output.getvalue())[0])

    def test_rejects_missing_file(self):
        body, content_type = multipart(fields={"language": "es"})
        self.assertEqual(400, self.request("POST", "/inference", body, {"Content-Type": content_type})[0])

    def test_rejects_truncated_wav_and_non_pcm_formats(self):
        for audio in [b"not wav", wav_bytes()[:-2], wav_bytes(rate=44100), wav_bytes(channels=2), wav_bytes(width=1)]:
            with self.subTest(length=len(audio)):
                self.assertEqual(400, self.inference(audio)[0])
        self.assertEqual([], self.runtime.calls)

    def test_rejects_parameter_expansion_and_other_languages(self):
        for fields in [{"language": "en"}, {"response_format": "text"}, {"model": "/tmp/model.bin"}]:
            with self.subTest(fields=fields):
                self.assertEqual(400, self.inference(fields=fields)[0])

    def test_rejects_duplicate_file_parts(self):
        body, content_type = multipart(wav_bytes())
        boundary = "ClassForgeWhisperProtocolTest"
        body = body.replace(f"--{boundary}--\r\n".encode(), b"") + body
        self.assertEqual(400, self.request("POST", "/inference", body, {"Content-Type": content_type})[0])

    def test_rejects_chunked_uploads(self):
        self.assertEqual(400, self.request("POST", "/inference", b"", {"Transfer-Encoding": "chunked"})[0])

    def test_does_not_expose_model_reload(self):
        self.assertEqual(404, self.request("POST", "/load", b"model=/tmp/arbitrary")[0])

    def test_inference_deadline_aborts_runtime_and_makes_health_unready(self):
        self.runtime.timeout = True
        self.assertEqual(504, self.inference()[0])
        self.assertTrue(self.runtime.aborted)
        self.assertEqual(503, self.request()[0])

    def test_unavailable_runtime_does_not_accept_audio(self):
        self.runtime.available = False
        self.assertEqual(503, self.inference()[0])
        self.assertEqual([], self.runtime.calls)


class UpstreamHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        self.send_response(self.server.health_status)
        self.end_headers()
        self.wfile.write(self.server.health_body)

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        self.server.request = (self.path, self.headers["Content-Type"], body)
        time.sleep(self.server.delay)
        self.send_response(200)
        self.end_headers()
        try:
            self.wfile.write(self.server.inference_body)
        except OSError:
            pass


class UpstreamTests(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), UpstreamHandler)
        self.server.health_status = 200
        self.server.health_body = b'{"status":"ok"}'
        self.server.inference_body = b'{"text":"Agrega una clase Cliente."}'
        self.server.delay = 0
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        self.thread.start()
        # Exercise real HTTP against a local upstream without spawning a native binary.
        self.runtime = object.__new__(whisper.WhisperRuntime)
        self.runtime.port = self.server.server_port
        self.runtime.process = type("RunningChild", (), {"poll": lambda _: None})()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)

    def test_health_requires_live_child_and_ready_json_from_upstream(self):
        self.assertTrue(self.runtime.ready())
        for status, body in [(503, b'{"status":"loading model"}'), (200, b'{"status":"loading model"}'), (200, b"broken json"), (200, b"[]")]:
            with self.subTest(status=status, body=body):
                self.server.health_status = status
                self.server.health_body = body
                self.assertFalse(self.runtime.ready())
        self.server.health_body = b'{"status":"ok"}'
        self.runtime.process = type("StoppedChild", (), {"poll": lambda _: 1})()
        self.assertFalse(self.runtime.ready())

    def test_upstream_receives_canonical_spanish_json_request_and_audio(self):
        audio = wav_bytes()
        self.assertEqual("Agrega una clase Cliente.", self.runtime.transcribe(audio, "UML, clase"))
        path, content_type, body = self.server.request
        self.assertEqual("/inference", path)
        message = BytesParser(policy=policy.default).parsebytes(b"Content-Type: " + content_type.encode() + b"\r\n\r\n" + body)
        parts = {part.get_param("name", header="content-disposition"): part for part in message.iter_parts()}
        self.assertEqual(b"es", parts["language"].get_payload(decode=True))
        self.assertEqual(b"json", parts["response_format"].get_payload(decode=True))
        self.assertEqual(b"0.0", parts["temperature_inc"].get_payload(decode=True))
        self.assertEqual("classforge-voice.wav", parts["file"].get_filename())
        self.assertEqual(audio, parts["file"].get_payload(decode=True))

    def test_does_not_accept_upstream_errors_or_partial_transcripts(self):
        for body in [b'{"error":"failed to read audio data"}', b'{"text":', b'{"text":42}', b"x" * (whisper.MAX_RESPONSE_BYTES + 1)]:
            with self.subTest(length=len(body)):
                self.server.inference_body = body
                with self.assertRaises((RuntimeError, ValueError)):
                    self.runtime.transcribe(wav_bytes(), "UML")

    def test_real_http_inference_obeys_deadline(self):
        previous_timeout = whisper.INFERENCE_TIMEOUT_SECONDS
        whisper.INFERENCE_TIMEOUT_SECONDS = 0.05
        self.server.delay = 0.2
        try:
            with self.assertRaises(TimeoutError):
                self.runtime.transcribe(wav_bytes(), "UML")
        finally:
            whisper.INFERENCE_TIMEOUT_SECONDS = previous_timeout


if __name__ == "__main__":
    unittest.main()
