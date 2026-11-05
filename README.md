# ScanBook (prototipo v0.1)

Fotos de las páginas de un libro → PDF y Word. App Android + servidor Python.

## Cómo funciona
1. App: **Escanear** (cámara, recorta y endereza con ML Kit, también importa de galería) o **Galería**. Reordena con ↑ ↓, borra con ✕.
2. **Generar**: sube las fotos al servidor, espera, y guarda 3 archivos en `Descargas/ScanBook`:
   - `libro_fiel.pdf`: la foto de cada página + texto invisible encima (buscable). Visualmente idéntico.
   - `libro_reconstruido.pdf`: texto vectorial en su posición + imágenes recortadas en su sitio. Aproximado (fuente parecida, no igual).
   - `libro.docx`: párrafos, títulos (por tamaño de letra), imágenes en orden de lectura. Se quita el número de página y se unen párrafos partidos entre páginas.

## Servidor
```
cd server
docker build -t scanbook . && docker run -p 8000:8000 -v scanbook-data:/data scanbook
```
Sin Docker: instalar Tesseract con idioma `spa`, luego `pip install -r requirements.txt` y `uvicorn main:app --host 0.0.0.0 --port 8000`.

Prueba por consola, sin app: `python pipeline.py foto1.jpg foto2.jpg` (crea `salida/`).

En la app, pon la URL: `http://IP-DE-TU-PC:8000` (misma red Wi-Fi). El servidor no tiene contraseña: úsalo solo en red local o detrás de un túnel con autenticación. Los resultados se borran a las 24 h.

## APK
Sube esta carpeta a GitHub. Actions compila solo: pestaña **Actions → Build APK → artifact ScanBook-apk**. O en local con Android Studio: abrir `android/` y *Run*. Instalar el `.apk` permitiendo "orígenes desconocidos".

## Límites conocidos
- Libros con 2 columnas, tablas o texto sobre imágenes: salida irregular.
- Página curva en el lomo: el texto se lee peor. Mantener libro plano, luz pareja.
- Negrita/cursiva y fuente exacta no se detectan todavía.
- Idioma fijo `spa+eng` (cambiar en `MainActivity.kt`).
- Usar solo con libros propios, de dominio público o con permiso del autor.

## Servidor en Windows (sin Docker)
Doble clic en `server/iniciar_windows.bat` (como administrador la primera vez, para abrir el firewall). Instala Tesseract y dependencias, muestra tu IP y arranca en el puerto 8000. Requiere Python 3.12.
