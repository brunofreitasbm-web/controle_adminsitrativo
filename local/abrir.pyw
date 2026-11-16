"""Abre o Assinador como um app (janela própria, sem abas/login do navegador).

Inicia o servidor local se preciso, abre o Edge/Chrome em modo --app com um perfil isolado
e, ao fechar a janela, encerra o servidor que ele mesmo iniciou.
"""
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

AQUI = Path(__file__).parent
PORTA = 5055
URL = f"http://127.0.0.1:{PORTA}"
NAVEGADORES = [
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    os.path.expandvars(r"%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe"),
]


def no_ar() -> bool:
    with socket.socket() as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", PORTA)) == 0


servidor = None
if not no_ar():
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    servidor = subprocess.Popen([str(pythonw if pythonw.exists() else sys.executable), str(AQUI / "app.py")],
                                cwd=AQUI, creationflags=0x08000000)  # sem janela de console
    for _ in range(50):
        if no_ar():
            break
        time.sleep(0.2)

navegador = next((n for n in NAVEGADORES if os.path.exists(n)), None)
if navegador:
    perfil = AQUI / "dados" / "perfil-navegador"
    subprocess.run([navegador, f"--app={URL}", f"--user-data-dir={perfil}", "--no-first-run",
                    "--window-size=900,820", "--disable-sync"])
else:
    import webbrowser
    webbrowser.open(URL)
    sys.exit()  # sem como saber quando fechou: deixa o servidor rodando

if servidor:
    servidor.terminate()
