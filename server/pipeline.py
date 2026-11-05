"""Pipeline: fotos de páginas -> PDF fiel (A), PDF reconstruido (B) y Word.

Sin dependencia de FastAPI: se puede usar desde consola con `python pipeline.py fotos/*.jpg`.
"""
import io
import re
import statistics
import sys
from dataclasses import dataclass, field

import cv2
import numpy as np
import pytesseract
import os as _os
import shutil as _shutil

_WIN = r"C:\Program Files\Tesseract-OCR\tesseract.exe"
if _os.name == "nt" and not _shutil.which("tesseract") and _os.path.exists(_WIN):
    pytesseract.pytesseract.tesseract_cmd = _WIN
from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.shared import Mm
from PIL import Image
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase.pdfmetrics import stringWidth
from reportlab.pdfgen import canvas

PAGE_W_PT = 420.0  # ancho de página PDF (aprox. A5). El alto sigue la proporción de la foto.
MAX_SIDE = 2600  # lado mayor máximo de la foto antes de procesar


@dataclass
class Line:
    text: str
    x: int
    y: int
    w: int
    h: int


@dataclass
class Para:
    lines: list
    font_h: float
    x: int
    y: int
    x2: int
    y2: int


@dataclass
class Figure:
    x: int
    y: int
    w: int
    h: int


@dataclass
class Page:
    img: Image.Image
    w: int
    h: int
    paras: list = field(default_factory=list)
    figs: list = field(default_factory=list)

    @property
    def lines(self):
        return [l for p in self.paras for l in p.lines]


# ---------------------------------------------------------------- análisis

def load_image(path):
    img = Image.open(path)
    try:
        from PIL import ImageOps
        img = ImageOps.exif_transpose(img)
    except Exception:
        pass
    img = img.convert("RGB")
    m = max(img.size)
    if m > MAX_SIDE:
        s = MAX_SIDE / m
        img = img.resize((int(img.width * s), int(img.height * s)), Image.LANCZOS)
    return img


def find_figures(img, lines):
    """Regiones grandes con 'tinta' que no son texto (fotos, dibujos, tablas gráficas)."""
    g = np.array(img.convert("L"))
    H, W = g.shape
    mask = g.copy()
    for l in lines:
        pad = 5
        mask[max(0, l.y - pad): l.y + l.h + pad, max(0, l.x - pad): l.x + l.w + pad] = 255
    _, bw = cv2.threshold(mask, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    k = cv2.getStructuringElement(cv2.MORPH_RECT, (max(15, W // 60), max(15, H // 60)))
    bw = cv2.morphologyEx(bw, cv2.MORPH_CLOSE, k)
    cnts, _ = cv2.findContours(bw, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    figs = []
    for c in cnts:
        x, y, w, h = cv2.boundingRect(c)
        if w < W * 0.12 or h < H * 0.06:
            continue
        if w > W * 0.97 and h > H * 0.97:
            continue
        if (bw[y:y + h, x:x + w] > 0).mean() < 0.15:
            continue
        figs.append(Figure(x, y, w, h))
    return sorted(figs, key=lambda f: f.y)


def analyze(path, lang="spa+eng"):
    img = load_image(path)
    W, H = img.size
    d = pytesseract.image_to_data(img, lang=lang, output_type=pytesseract.Output.DICT, config="--psm 3")
    groups = {}
    for i, t in enumerate(d["text"]):
        t = t.strip()
        try:
            conf = float(d["conf"][i])
        except ValueError:
            conf = -1
        if not t or conf < 20:
            continue
        key = (d["block_num"][i], d["par_num"][i])
        groups.setdefault(key, {}).setdefault(d["line_num"][i], []).append(i)

    raw = []  # (key, [Line])
    for key, lns in groups.items():
        out = []
        for _, idx in sorted(lns.items()):
            x = min(d["left"][i] for i in idx)
            y = min(d["top"][i] for i in idx)
            x2 = max(d["left"][i] + d["width"][i] for i in idx)
            y2 = max(d["top"][i] + d["height"][i] for i in idx)
            out.append(Line(" ".join(d["text"][i].strip() for i in idx), x, y, x2 - x, y2 - y))
        raw.append(out)

    flat = [l for ls in raw for l in ls]
    figs = find_figures(img, flat)

    def in_fig(l):
        cx, cy = l.x + l.w / 2, l.y + l.h / 2
        return any(f.x <= cx <= f.x + f.w and f.y <= cy <= f.y + f.h for f in figs)

    page = Page(img=img, w=W, h=H, figs=figs)
    for ls in raw:
        ls = [l for l in ls if not in_fig(l)]
        if not ls:
            continue
        fh = statistics.median(l.h for l in ls)
        page.paras.append(Para(ls, fh, min(l.x for l in ls), min(l.y for l in ls),
                               max(l.x + l.w for l in ls), max(l.y + l.h for l in ls)))
    page.paras.sort(key=lambda p: p.y)
    return page


# ---------------------------------------------------------------- PDF

def _safe(text):
    return text.encode("cp1252", "replace").decode("cp1252")


def _put_line(c, l, fh, s, ph, font, invisible):
    text = _safe(l.text)
    size = max(4.0, fh * s * 0.78)
    nat = stringWidth(text, font, size)
    hs = max(50.0, min(200.0, l.w * s / nat * 100)) if nat > 0 else 100.0
    t = c.beginText()
    if invisible:
        t.setTextRenderMode(3)
    t.setFont(font, size)
    t.setHorizScale(hs)
    t.setTextOrigin(l.x * s, ph - (l.y + l.h - fh * 0.22) * s)
    t.textOut(text)
    c.drawText(t)


def build_pdf_a(pages, path):
    """PDF fiel: foto de la página + capa de texto invisible (buscable y seleccionable)."""
    c = None
    for p in pages:
        s = PAGE_W_PT / p.w
        pw, ph = PAGE_W_PT, p.h * s
        if c is None:
            c = canvas.Canvas(path, pagesize=(pw, ph))
        c.setPageSize((pw, ph))
        c.drawImage(ImageReader(p.img), 0, 0, pw, ph)
        for para in p.paras:
            for l in para.lines:
                _put_line(c, l, para.font_h, s, ph, "Helvetica", True)
        c.showPage()
    c.save()


def build_pdf_b(pages, path, serif=True):
    """PDF reconstruido: texto vectorial en su posición + imágenes recortadas en su sitio."""
    font = "Times-Roman" if serif else "Helvetica"
    c = None
    for p in pages:
        s = PAGE_W_PT / p.w
        pw, ph = PAGE_W_PT, p.h * s
        if c is None:
            c = canvas.Canvas(path, pagesize=(pw, ph))
        c.setPageSize((pw, ph))
        for f in p.figs:
            crop = p.img.crop((f.x, f.y, f.x + f.w, f.y + f.h))
            c.drawImage(ImageReader(crop), f.x * s, ph - (f.y + f.h) * s, f.w * s, f.h * s)
        for para in p.paras:
            for l in para.lines:
                _put_line(c, l, para.font_h, s, ph, font, False)
        c.showPage()
    c.save()


# ---------------------------------------------------------------- Word

_PAGE_NUM = re.compile(r"^\s*[\d\-–—ivxlcIVXLC.]+\s*$")


def _para_text(lines):
    out = ""
    for l in lines:
        t = l.text
        if out.endswith("-") and t[:1].islower():
            out = out[:-1] + t
        else:
            out = (out + " " + t).strip()
    return out


def build_docx(pages, path):
    """Word fluido: párrafos, títulos por tamaño de letra, imágenes recortadas en orden de lectura."""
    sizes = [para.font_h for p in pages for para in p.paras]
    base = statistics.median(sizes) if sizes else 1.0

    items = []  # dicts: kind p|f
    for pi, p in enumerate(pages):
        entries = []
        for para in p.paras:
            text = _para_text(para.lines)
            at_edge = para.y2 < p.h * 0.08 or para.y > p.h * 0.92
            if at_edge and _PAGE_NUM.match(text):
                continue  # número de página
            centered = abs((para.x + para.x2) / 2 - p.w / 2) < p.w * 0.04 and para.x > p.w * 0.1
            entries.append((para.y, {"k": "p", "t": text, "r": para.font_h / base,
                                     "c": centered, "first": False}))
        for f in p.figs:
            crop = p.img.crop((f.x, f.y, f.x + f.w, f.y + f.h))
            entries.append((f.y, {"k": "f", "img": crop, "frac": f.w / p.w}))
        entries.sort(key=lambda e: e[0])
        for n, (_, it) in enumerate(entries):
            it["first"] = n == 0 and pi > 0
            items.append(it)

    # unir párrafos partidos entre páginas
    merged = []
    for it in items:
        if (it["k"] == "p" and it["first"] and merged and merged[-1]["k"] == "p"
                and merged[-1]["r"] < 1.3 and not re.search(r"[.!?:»”\"]$", merged[-1]["t"])
                and it["t"][:1].islower()):
            prev = merged[-1]["t"]
            merged[-1]["t"] = (prev[:-1] + it["t"]) if prev.endswith("-") else prev + " " + it["t"]
        else:
            merged.append(it)

    doc = Document()
    sec = doc.sections[0]
    sec.page_width, sec.page_height = Mm(148), Mm(210)  # A5
    for side in ("left_margin", "right_margin", "top_margin", "bottom_margin"):
        setattr(sec, side, Mm(15))
    text_w = 148 - 30

    for it in merged:
        if it["k"] == "f":
            buf = io.BytesIO()
            it["img"].save(buf, "JPEG", quality=88)
            buf.seek(0)
            doc.add_picture(buf, width=Mm(max(20, min(text_w, text_w * it["frac"] / 0.8))))
            doc.paragraphs[-1].alignment = WD_ALIGN_PARAGRAPH.CENTER
        elif it["r"] >= 1.35 and len(it["t"]) < 120:
            h = doc.add_heading(it["t"], level=1 if it["r"] >= 1.8 else 2)
            if it["c"]:
                h.alignment = WD_ALIGN_PARAGRAPH.CENTER
        else:
            para = doc.add_paragraph(it["t"])
            para.alignment = WD_ALIGN_PARAGRAPH.CENTER if it["c"] else WD_ALIGN_PARAGRAPH.JUSTIFY
    doc.save(path)


# ---------------------------------------------------------------- consola

def run_all(paths, out_dir, lang="spa+eng", progress=None):
    from concurrent.futures import ThreadPoolExecutor
    import os
    pages = [None] * len(paths)
    done = 0

    def work(i):
        pages[i] = analyze(paths[i], lang)
        return i

    with ThreadPoolExecutor(max_workers=max(1, (os.cpu_count() or 2))) as ex:
        for _ in ex.map(work, range(len(paths))):
            done += 1
            if progress:
                progress(done, len(paths))
    build_pdf_a(pages, f"{out_dir}/libro_fiel.pdf")
    build_pdf_b(pages, f"{out_dir}/libro_reconstruido.pdf")
    build_docx(pages, f"{out_dir}/libro.docx")


if __name__ == "__main__":
    import os
    out = "salida"
    os.makedirs(out, exist_ok=True)
    run_all(sorted(sys.argv[1:]), out, progress=lambda a, b: print(f"{a}/{b}"))
    print("listo ->", out)
