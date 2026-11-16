# Assinador local (Python)

Versão para o seu PC, sem instalar EXE. Roda em `http://127.0.0.1:5055` (só esta máquina enxerga).

1. Dê dois cliques em `iniciar.bat` (instala as dependências na primeira vez e abre o navegador).
2. Primeira vez: escolha o .pfx e digite a senha → **Salvar**. Fica guardado em `local/dados/`
   (a senha é cifrada com o Windows DPAPI: só abre na sua conta deste PC).
3. Arraste um ou vários PDFs, clique na página para posicionar o selo, **ASSINAR**.
4. Saída: `Documentos\Assinados\<nome>_assinado.pdf`.

Mesmo padrão do app Android: PAdES AD-RB v1.3 (ICP-Brasil), SHA-256, sem carimbo de tempo.
Páginas com rotação (/Rotate) não têm o posicionamento tratado. Teste sem certificado real: `python teste_local.py`.
