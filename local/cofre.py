"""Guarda o certificado A1 e a senha só neste computador.

A senha é cifrada com o DPAPI do Windows: só é possível decifrá-la logado na sua conta
do Windows, neste PC. Fora do Windows não salva a senha (recusa), para não deixá-la em texto puro.
"""
import ctypes
import ctypes.wintypes as wt
import os
import sys
from pathlib import Path

DADOS = Path(__file__).parent / "dados"
PFX = DADOS / "certificado.pfx"
SENHA = DADOS / "senha.bin"


class _Blob(ctypes.Structure):
    _fields_ = [("cbData", wt.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]


def _blob(b: bytes) -> _Blob:
    return _Blob(len(b), ctypes.cast(ctypes.create_string_buffer(b, len(b)), ctypes.POINTER(ctypes.c_char)))


def _dpapi(data: bytes, proteger: bool) -> bytes:
    entrada, saida = _blob(data), _Blob()
    fn = ctypes.windll.crypt32.CryptProtectData if proteger else ctypes.windll.crypt32.CryptUnprotectData
    if not fn(ctypes.byref(entrada), None, None, None, None, 0, ctypes.byref(saida)):
        raise OSError("DPAPI falhou")
    try:
        return ctypes.string_at(saida.pbData, saida.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(saida.pbData)


WINDOWS = sys.platform == "win32"


def salvar(pfx: bytes, senha: str) -> None:
    if not WINDOWS:
        raise OSError("Guardar a senha só é suportado no Windows (DPAPI)")
    DADOS.mkdir(exist_ok=True)
    PFX.write_bytes(pfx)
    bruto = senha.encode("utf-8")
    SENHA.write_bytes(_dpapi(bruto, True))


def existe() -> bool:
    return PFX.exists() and SENHA.exists()


def carregar() -> tuple[bytes, str]:
    bruto = SENHA.read_bytes()
    senha = _dpapi(bruto, False).decode("utf-8")
    return PFX.read_bytes(), senha


def remover() -> None:
    for p in (PFX, SENHA):
        if p.exists():
            p.unlink()
