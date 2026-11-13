# ClassForge en una VM de Azure

Destino elegido por el usuario el10nov2026: **VM Ubuntu24.04 LTS x64**, Java21,
nginx HTTPS/WSS, systemd y JAR completo con Angular. El repositorio examen2Software
usa GPT mediante Codex y Whisper local. Ver [configuración del asistente](assistant-codex.md).
Despliegue activo: [ClassForge](https://classforge-tiago-15124.eastus.cloudapp.azure.com).
Se publicó el10nov2026 en una VM Standard_D2als_v7 en East US, con MongoDB Atlas
Free. La [evidencia del despliegue real](../evidence/parcial2/azure-vm-live.md)
registra las comprobaciones. Los siguientes pasos permiten reproducirlo.

## Archivos y requisitos

- Infraestructura: `deploy/azure/vm/main.bicep` y `scripts/azure-vm-provision.ps1`.
- Paquete: `scripts/azure-vm-build-bundle.ps1` → `.azure-deploy/classforge-azure-vm.zip`
  y checksum. Incluye JAR, instalador, servicio, nginx, TLS y env de ejemplo.
- Smoke público: `scripts/azure-smoke.ps1`.
- Workflow manual: **Build Azure VM release**; solo prueba y genera el bundle.

Para compilar: JDK21, Node24 compatible, PowerShell y MongoDB replica set para
pruebas. Para crear recursos: Azure CLI, suscripción seleccionada y cuota regional.
Para publicar: cliente SSH/SCP, clave SSH y permisos sudo en Ubuntu.
Tamaño recomendado: Standard_B2als_v2 (2 vCPU, 4 GiB); comprobar disponibilidad y costo de VM,
disco e IP en la suscripción. No se requiere GPU. Mantener una sola instancia:
el broker STOMP y la presencia siguen en memoria.

La alternativa previa de [App Service](azure-app-service-deployment.md) se conserva
como referencia. Sus comandos `azure-provision.ps1` y `azure-deploy.ps1` no forman
parte de este procedimiento VM. No crear un plan App Service para seguir esta guía.

## 1. Si ya existe una VM

Se puede omitir Bicep. Confirmar Ubuntu24.04 x64, disco persistente, IP pública
estática y un FQDN que resuelva a ella. En el NSG permitir22 solo desde la IP
administrativa y80/443 desde Internet. No publicar8082 ni27017. Asegurar salida
HTTPS a Groq y conexión al replica set Mongo. El instalador es para una VM dedicada:
revisar cualquier nginx/systemd existente antes de usarlo.

## 2. Crear una VM nueva (opcional, con costo)

Crear una clave dedicada si no existe, conservando la clave privada en el equipo:

```powershell
ssh-keygen -t ed25519 -f "$env:USERPROFILE/.ssh/classforge_azure"
Copy-Item deploy/azure/vm/parameters.example.json deploy/azure/vm/parameters.local.json
```

Editar `parameters.local.json` (ignorado por Git): `vmName`, `adminUsername`,
`sshPublicKey` con el contenido del archivo **.pub**, `adminPublicIp` con la IPv4
actual del administrador sin `/32`, y `dnsLabel` regionalmente único. No introducir
la clave privada. El script limita SSH a esa IP/32; si cambia la IP habrá que
actualizar la regla mediante Azure.

```powershell
az login
az account set --subscription <id-de-tu-suscripcion>
.\scripts\azure-vm-provision.ps1 -ResourceGroup rg-classforge-vm `
  -ParametersFile deploy/azure/vm/parameters.local.json -Location brazilsouth -Mode Create
```

En un grupo existente, usar `-Mode Validate` o `-Mode WhatIf` antes de Create.
La plantilla crea VM, disco StandardSSD32GB, NIC, VNet, NSG e IP Standard estática.
La NIC usa esa IP también como salida explícita; no depende de default outbound.
La imagen es `Canonical:ubuntu-24_04-lts:server:latest`, con SSH por clave y
Trusted Launch. Si la suscripción no admite el tamaño/imagen en esa región,
elegir un tamaño compatible y revisar la cuota antes de repetir.
Usar los outputs `fqdn` e `ipAddress` reales, sin adivinar el nombre DNS.

## 3. MongoDB y secretos

El usuario confirmó **MongoDB Atlas**. La VM aloja Java/nginx y Atlas conserva
los datos mediante CLASSFORGE_MONGODB_URI. No instalar MongoDB en esta VM.

En Atlas, crear un usuario `readWrite` para `classforge` y añadir la IP pública
estática de la VM a la allowlist. La URI debe nombrar explícitamente la base:

```text
mongodb+srv://USUARIO:CLAVE_CODIFICADA@CLUSTER/classforge?retryWrites=true&w=majority
```

No migrar datos locales automáticamente ni usar el perfil demo para datos reales.
La base de nube comienza vacía si no se importa una copia. Los pasos de base
Atlas y cuotas están también en [asistente Groq](assistant-groq.md).

Los secretos viven en `/etc/classforge/classforge.env`, root:root0600. El bundle
contiene únicamente un ejemplo vacío. En el equipo puede existir la preparación
privada `deploy/azure/vm/classforge.local.env`; completar URI y origen antes de
transferirla. No incluir ese archivo en Git ni en ZIP.

## 4. Construir y transferir

```powershell
docker compose -f compose.mongo.yml up -d --wait
.\scripts\azure-vm-build-bundle.ps1
```

`-UseExistingPackage` reutiliza solo un JAR completo con SHA correcto y perfil VM.
`-SkipTests -SkipInstall` sirve para empaquetar una revisión ya verificada. El
workflow manual de GitHub hace tests y genera el mismo ZIP, sin acceder a Azure.
Si el workflow de la rama `tiago` todavía no aparece en Actions por no estar en
la rama predeterminada, usar el script local; no se fusiona automáticamente a main.

Conectar primero con SSH y verificar la identidad de la VM. Luego transferir
paquete y checksum, sustituyendo `HOSTNAME` por el FQDN real:

```powershell
scp -i "$env:USERPROFILE/.ssh/classforge_azure" `
  .azure-deploy/classforge-azure-vm.zip .azure-deploy/classforge-azure-vm.zip.sha256 azureuser@HOSTNAME:~/
ssh -i "$env:USERPROFILE/.ssh/classforge_azure" azureuser@HOSTNAME
```

En la VM:

```bash
sha256sum --check classforge-azure-vm.zip.sha256
sudo apt-get update && sudo apt-get install -y unzip
mkdir -p ~/classforge-release
unzip -o classforge-azure-vm.zip -d ~/classforge-release
cd ~/classforge-release
sudo bash install.sh HOSTNAME
```

El instalador verifica el JAR antes de instalar, crea el usuario `classforge`,
instala Java21/nginx/Certbot y habilita el servicio sin arrancar la app.
Preserva env y TLS existentes. Las versiones viven en `/opt/classforge/releases/<sha>`;
`current` apunta a la versión elegida. No borra versiones anteriores ni bases.

## 5. Configurar y activar HTTPS

Editar el archivo en la VM (o transferir el archivo privado y colocarlo con
`sudo install -o root -g root -m 0600 ... /etc/classforge/classforge.env`):

```bash
sudoedit /etc/classforge/classforge.env
```

Completar `CLASSFORGE_MONGODB_URI`, un JWT aleatorio mínimo64 caracteres,
`CLASSFORGE_CODEX_EXECUTABLE`, `CLASSFORGE_CODEX_HOME` y
`CLASSFORGE_WEBSOCKET_ALLOWED_ORIGINS=https://HOSTNAME` exacto. Instalar los
runtimes y autorizar Codex según [la guía GPT/Codex](assistant-codex.md).
Usar valores KEY=value sin `export`; no configurar SERVER_PORT. El servicio carga
`azure,azure-vm` y escucha exclusivamente127.0.0.1:8082.

Verificar que HOSTNAME resuelva a la VM y80/443 estén abiertos antes de Certbot:

```bash
sudo /opt/classforge/bin/enable-https.sh HOSTNAME TU_CORREO
sudo certbot renew --dry-run
sudo systemctl restart classforge
sudo systemctl status classforge --no-pager
curl --fail http://127.0.0.1:8082/api/health
```

Antes de HTTPS, nginx solo sirve el challenge ACME y devuelve503 para la app.
Después, HTTP redirige a HTTPS y nginx hace proxy de Angular/API/WSS al backend.
Certbot utiliza webroot, un timer y un hook que recarga nginx al renovar. Si la
autoridad no emite para el FQDN elegido, usar un dominio propio apuntado a la IP
y repetir con ese nombre y origen. No usar certificados autofirmados para la demo
en navegadores ajenos: voz requiere contexto seguro y permisos del navegador.

No se activan el perfil demo ni sus contraseñas. Registrar cuentas desde la web
y crear/invitar al proyecto normalmente.

## 6. Comprobar la publicación

El lector de imágenes usa una sola consulta visual a GPT, con validación de
evidencia, referencias, revisión y preview antes de Apply. Mantener
`CLASSFORGE_ASSISTANT_VISION_TWO_PASS=false` y
`CLASSFORGE_ASSISTANT_VISION_HYBRID=false` para una sola consulta: el modo híbrido
realiza consultas adicionales por conector y multiplicidad que pueden agotar
la cuota aunque la primera lectura sea válida. Puede habilitarse explícitamente
en entornos con cuota suficiente. Un HTTP429 se muestra con el tiempo de espera
del proveedor; el análisis no aplica cambios automáticamente.

Desde el equipo:

```powershell
.\scripts\azure-smoke.ps1 -BaseUrl https://HOSTNAME
```

Comprueba health, rutas profundas Angular, API privada401 y handshake WSS.
Después usar dos cuentas/navegadores para verificar presencia, colaboración,
chat persistido, ASK/REVIEW/SUMMARY_CURRENT, EDIT con preview/Apply, voz, imagen
y exportaciones. El smoke público no llama a Groq ni valida un CONNECT STOMP
autenticado; esas pruebas necesitan cuentas/proyecto.

## Actualizar, recuperar y operar

Transferir un bundle nuevo, extraerlo, ejecutar `sudo bash install.sh HOSTNAME`
y `sudo systemctl restart classforge`. El archivo env y nginx TLS se conservan.
Comprobar smoke tras actualizar. Para volver a un JAR anterior:

```bash
ls /opt/classforge/releases
sudo ln -sfn /opt/classforge/releases/SHA_ANTERIOR /opt/classforge/current.next
sudo mv -Tf /opt/classforge/current.next /opt/classforge/current
sudo systemctl restart classforge
```

Esto revierte código, no datos. Mantener backups de Mongo por separado.
Logs: `sudo journalctl -u classforge -n 100 --no-pager`,
`sudo nginx -t` y `/var/log/nginx/error.log`. No compartir logs que contengan
credenciales. Aplicar actualizaciones Ubuntu y vigilar espacio/CPU/memoria.
Apagar dentro de Linux no equivale a desasignar Azure; disco e IP pueden seguir
cobrando incluso tras desasignar la VM. Revisar recursos/costos al terminar.

## Fuentes oficiales

- [Imágenes Ubuntu Azure](https://ubuntu.com/azure/docs/azure-how-to/find-ubuntu-images/).
- [IP pública estática y DNS](https://learn.microsoft.com/en-us/azure/virtual-network/ip-services/public-ip-addresses/).
- [Salida explícita Azure](https://learn.microsoft.com/en-us/azure/virtual-network/ip-services/default-outbound-access).
- [Certificados Ubuntu](https://ubuntu.com/server/docs/how-to/security/obtain-tls-certificates/).
- [Certbot](https://certbot.eff.org/instructions?os=snap&ws=nginx).
- [Bicep VM](https://learn.microsoft.com/en-us/azure/templates/microsoft.compute/virtualmachines).
- [Tamaños Basv2](https://learn.microsoft.com/en-us/azure/virtual-machines/sizes/general-purpose/basv2-series).
