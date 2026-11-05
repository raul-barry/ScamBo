"""API: crea trabajo -> sube páginas una a una -> start -> consulta progreso -> descarga."""
import os
import shutil
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse

import pipeline

DATA = Path(os.environ.get("DATA_DIR", "/tmp/scanbook"))
DATA.mkdir(parents=True, exist_ok=True)
KEEP_SECONDS = 24 * 3600  # privacidad: borra todo tras 24 h

app = FastAPI(title="ScanBook")
JOBS = {}

FILES = {
    "pdf_a": ("libro_fiel.pdf", "application/pdf"),
    "pdf_b": ("libro_reconstruido.pdf", "application/pdf"),
    "docx": ("libro.docx",
             "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
}


def _cleanup():
    now = time.time()
    for d in DATA.iterdir():
        if d.is_dir() and now - d.stat().st_mtime > KEEP_SECONDS:
            shutil.rmtree(d, ignore_errors=True)
            JOBS.pop(d.name, None)


def _job(jid):
    j = JOBS.get(jid)
    if not j:
        raise HTTPException(404, "trabajo no existe")
    return j


@app.get("/health")
def health():
    return {"ok": True}


@app.post("/jobs")
def create_job(lang: str = Form("spa+eng")):
    _cleanup()
    jid = uuid.uuid4().hex[:12]
    (DATA / jid / "pages").mkdir(parents=True)
    JOBS[jid] = {"status": "uploading", "pages": 0, "done": 0, "error": "", "lang": lang}
    return {"id": jid}


@app.post("/jobs/{jid}/pages")
async def add_page(jid: str, index: int = Form(...), file: UploadFile = File(...)):
    j = _job(jid)
    if j["status"] != "uploading":
        raise HTTPException(409, "el trabajo ya empezó")
    (DATA / jid / "pages" / f"{index:05d}.jpg").write_bytes(await file.read())
    j["pages"] += 1
    return {"pages": j["pages"]}


def _run(jid):
    j = JOBS[jid]
    d = DATA / jid
    try:
        paths = sorted((d / "pages").glob("*.jpg"))
        pages = [None] * len(paths)

        def work(i):
            pages[i] = pipeline.analyze(paths[i], j["lang"])
            j["done"] += 1

        with ThreadPoolExecutor(max_workers=max(1, os.cpu_count() or 2)) as ex:
            list(ex.map(work, range(len(paths))))
        j["status"] = "building"
        pipeline.build_pdf_a(pages, str(d / "pdf_a.pdf"))
        pipeline.build_pdf_b(pages, str(d / "pdf_b.pdf"))
        pipeline.build_docx(pages, str(d / "docx.docx"))
        shutil.rmtree(d / "pages", ignore_errors=True)  # fotos fuera; quedan solo los resultados
        j["status"] = "done"
    except Exception as e:  # noqa: BLE001
        j["status"] = "error"
        j["error"] = f"{type(e).__name__}: {e}"


@app.post("/jobs/{jid}/start")
def start(jid: str):
    j = _job(jid)
    if j["pages"] == 0:
        raise HTTPException(400, "sin páginas")
    j["status"] = "processing"
    threading.Thread(target=_run, args=(jid,), daemon=True).start()
    return {"ok": True}


@app.get("/jobs/{jid}")
def status(jid: str):
    j = _job(jid)
    return {"status": j["status"], "pages": j["pages"], "done": j["done"], "error": j["error"]}


@app.get("/jobs/{jid}/file/{kind}")
def download(jid: str, kind: str):
    j = _job(jid)
    if kind not in FILES or j["status"] != "done":
        raise HTTPException(404, "no disponible")
    name, mime = FILES[kind]
    ext = "docx" if kind == "docx" else "pdf"
    return FileResponse(DATA / jid / f"{kind}.{ext}", media_type=mime, filename=name)
