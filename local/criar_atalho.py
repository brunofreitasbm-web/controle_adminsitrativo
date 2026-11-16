"""Cria o ícone e o atalho 'Assinar PDF' na Área de Trabalho."""
import os
import struct
import subprocess
import sys
from pathlib import Path

import pymupdf

AQUI = Path(__file__).parent.resolve()
SVG = """<svg xmlns='http://www.w3.org/2000/svg' width='256' height='256' viewBox='0 0 256 256'>
<rect width='256' height='256' rx='48' fill='#1f5078'/>
<path d='M70 40h84l40 40v136a8 8 0 0 1-8 8H70a8 8 0 0 1-8-8V48a8 8 0 0 1 8-8z' fill='#fff'/>
<path d='M154 40v40h40z' fill='#bcd0e0'/>
<path d='M84 170c18-30 30-30 36-6 4 14 14 6 22-6 6-8 12-8 16 2' fill='none' stroke='#1f5078' stroke-width='9' stroke-linecap='round'/>
<rect x='84' y='190' width='88' height='7' rx='3' fill='#1f5078'/></svg>"""

doc = pymupdf.open(stream=SVG.encode(), filetype="svg")
png = pymupdf.open("pdf", doc.convert_to_pdf())[0].get_pixmap(matrix=pymupdf.Matrix(1, 1), alpha=True).tobytes("png")
ico = AQUI / "assinador.ico"
ico.write_bytes(struct.pack("<HHH", 0, 1, 1) + struct.pack("<BBBBHHII", 0, 0, 0, 0, 1, 32, len(png), 22) + png)

pythonw = Path(sys.executable).with_name("pythonw.exe")
ps = f"""
$d=[Environment]::GetFolderPath('Desktop'); $s=(New-Object -ComObject WScript.Shell).CreateShortcut("$d\Assinar PDF.lnk")
$s.TargetPath='{pythonw}'; $s.Arguments='"{AQUI / 'abrir.pyw'}"'; $s.WorkingDirectory='{AQUI}'
$s.IconLocation='{ico}'; $s.Description='Assinar PDF com certificado A1'; $s.Save()"""
subprocess.run(["powershell", "-NoProfile", "-Command", ps], check=True)
print("Atalho criado na Área de Trabalho: Assinar PDF")
