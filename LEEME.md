# Gym Registro · App para Android

App Android que abre la plataforma web del gimnasio (Google Apps Script) y añade:
- Icono propio y pantalla de inicio.
- Escáner de QR (botón redondo abajo a la derecha) para abrir el perfil del cliente desde su carné.
- Pantalla de "Sin conexión" con botón Reintentar.
- WhatsApp y la hoja de Google se abren en sus propias apps.
- Botón atrás: hay que pulsarlo dos veces para salir.

## 1. Pon tu enlace
Edita `app/src/main/res/values/config.xml` y pega tu enlace de Apps Script (el que termina en `/exec`) en `app_url`.
Si lo dejas vacío, la app pedirá el enlace la primera vez que se abra.
Opcional: escribe un código en `gym_code` (por ejemplo `TITAN`) para hacer una app exclusiva de ese gimnasio.

## 2. Compila el APK en GitHub (gratis)
1. Crea una cuenta en github.com y un repositorio nuevo (puede ser privado).
2. Pulsa "uploading an existing file" y sube TODO el contenido de esta carpeta (incluida la carpeta `.github`).
3. Ve a la pestaña **Actions**: la compilación empieza sola y tarda unos 5 minutos.
4. Cuando termine (marca verde), ve a **Releases** y descarga `GymRegistro-v1.0.X.apk`.

Cada vez que cambies algo y lo subas, se genera un APK nuevo que se instala encima del anterior sin perder la sesión.

## 3. Instálalo en el teléfono
Abre el APK en el teléfono. Si Android lo bloquea, permite "Instalar apps desconocidas" para el navegador o el gestor de archivos.

## Notas
- La firma del APK está en `app/gymregistro.keystore`. No la borres: si cambia, los teléfonos no aceptarán las actualizaciones.
- Mantén pulsado el botón del escáner para cambiar el enlace de la plataforma.
