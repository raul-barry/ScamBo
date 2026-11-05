@echo off
setlocal
cd /d "%~dp0"
where python >nul 2>nul || (echo Instala Python 3.12 desde python.org y marca Add python.exe to PATH & pause & exit /b 1)
if not exist "%ProgramFiles%\Tesseract-OCR\tesseract.exe" (
  echo Instalando Tesseract OCR...
  winget install -e --id UB-Mannheim.TesseractOCR --accept-source-agreements --accept-package-agreements
)
if not exist tessdata mkdir tessdata
if not exist tessdata\spa.traineddata powershell -Command "Invoke-WebRequest https://github.com/tesseract-ocr/tessdata_fast/raw/main/spa.traineddata -OutFile tessdata\spa.traineddata"
if not exist tessdata\eng.traineddata powershell -Command "Invoke-WebRequest https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata -OutFile tessdata\eng.traineddata"
if not exist .venv python -m venv .venv
call .venv\Scripts\activate.bat
pip install -q -r requirements.txt
netsh advfirewall firewall add rule name="ScanBook" dir=in action=allow protocol=TCP localport=8000 >nul 2>nul
set TESSDATA_PREFIX=%~dp0tessdata
echo.
echo ===== En la app escribe http://TU-IP:8000 usando una de estas IPv4 =====
ipconfig | findstr /c:"IPv4"
echo ========================================================================
uvicorn main:app --host 0.0.0.0 --port 8000
pause
