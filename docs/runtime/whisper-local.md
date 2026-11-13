# whisper.cpp local para CU08-002

ClassForge usa `whisper-server` como runtime STT persistente.

## Puerto esperado

```text
127.0.0.1:8093
```

Spring invoca:

```text
POST /inference
multipart field: file
response_format: json
language: es
```

## Modelo recomendado inicial

Para español debe usarse un modelo multilingue, no un modelo `.en`.

Perfil sugerido:

```text
tiny   -> laptop lenta / menor latencia
base   -> default de demostracion
small  -> mayor precision si el hardware lo permite
```

## Ejemplo Windows

Desde el directorio que contiene `whisper-server.exe`:

```powershell
whisper-server.exe `
  -m ".\models\ggml-base.bin" `
  -l es `
  --host 127.0.0.1 `
  --port 8093 `
  -t 6 `
  -nfa `
  -nt
```

El frontend ya envia WAV PCM. No se requiere `--convert` ni ffmpeg para el flujo normal de ClassForge.

El puerto 8093 es independiente del `llama-server` de CU08-001, que permanece en 8092.

## Azure VM Ubuntu 24.04: instalacion sin API key

Desde la copia de **examen2Software** en la VM:

```bash
sudo bash deploy/azure/vm/install-whisper.sh
curl --fail http://127.0.0.1:8093/health
sudo systemctl status classforge-whisper --no-pager
```

El instalador fija whisper.cpp **v1.9.5** por commit y verifica el SHA256 del
modelo multilingue **base-q8_0** (82 MB). Compila para la CPU de esa VM con dos
hilos; el binario incluye whisper/ggml estaticos, por lo que no depende de los
archivos temporales de compilacion. Una vez descargado, el runtime funciona
sin red, cuentas ni claves. Requiere Python 3.12 de Ubuntu; no instala paquetes
de pip. No usa Codex para reconocer audio.

El servicio `classforge-whisper` ejecuta el servidor real whisper.cpp en
`127.0.0.1:8094`, detras de `whisper-local.py` en `127.0.0.1:8093`. No publiques
esos puertos en Nginx ni en el NSG. El backend conserva su contrato `/inference`
y `AssistantRuntimeProbe` verifica `/health`, que consulta el modelo cargado.

El ingreso acepta WAV PCM16 mono a 16 kHz, entre 0.25 segundos y **4 MiB**,
con idioma `es` y respuesta JSON. Permite una inferencia y cuatro conexiones;
una inferencia simultanea recibe HTTP 429. La carga tiene un plazo de tres
segundos y la inferencia de cuarenta, dentro de los 45 segundos del gateway.
Si la inferencia vence, termina el proceso C++ y systemd reinicia el servicio,
evitando dejar trabajo en cola. Usa ordenes breves de 5 a 15 segundos; la
latencia real depende de la CPU y debe medirse antes de la demo.

Los ejecutables viven en `/opt/classforge/whisper`; el modelo y un WAV de
prueba publico viven en `/opt/classforge/data/whisper`. Son archivos de root
legibles por `classforge`. El servicio corre con ese usuario, acceso de red
limitado a loopback, filesystem de solo lectura y un limite de memoria de
1 GiB. No guarda las grabaciones ni escribe transcripciones en el journal.
No lee `/etc/classforge`, los archivos de Codex ni variables de la aplicacion.

El instalador comprueba health y transcribe el WAV de ejemplo de upstream.
Para verificar reconocimiento en español con una grabacion del navegador:

```bash
curl --fail --max-time 45 http://127.0.0.1:8093/inference \
  -F 'file=@/ruta/orden-es.wav;type=audio/wav' \
  -F language=es -F response_format=json
sudo journalctl -u classforge-whisper -n 40 --no-pager
```

La prueba del protocolo no descarga modelos ni llama proveedores:

```bash
python3 -B -m unittest discover -s deploy/azure/vm/tests -v
bash -n deploy/azure/vm/install-whisper.sh
```

Referencias primarias: [whisper.cpp v1.9.5](https://github.com/ggml-org/whisper.cpp/releases/tag/v1.9.5),
[servidor y protocolo](https://github.com/ggml-org/whisper.cpp/tree/v1.9.5/examples/server),
[modelo y SHA256](https://huggingface.co/ggerganov/whisper.cpp/blob/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base-q8_0.bin).
