"""Assinador de PDF local (A1, PAdES AD-RB). Roda só em 127.0.0.1 — nada sai do seu computador."""
import hmac
import os
import io
import re
import secrets
import time
from pathlib import Path

import pymupdf as fitz
from flask import Flask, abort, jsonify, render_template_string, request, send_file

import assinar
import cofre

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 200 * 1024 * 1024
SAIDA = Path.home() / "Downloads" / "Assinados"


TOKEN = secrets.token_urlsafe(32)  # novo a cada execução; só a nossa página (mesma origem) o conhece
HOSTS = {"127.0.0.1:5055", "localhost:5055"}


@app.before_request
def proteger():
    # Anti DNS-rebinding: só aceita Host local. Anti-CSRF: exige o token num header customizado
    # (sites externos não conseguem enviá-lo sem preflight CORS, que não aprovamos).
    if request.host not in HOSTS:
        abort(403)
    if request.path.startswith("/api/"):
        if not hmac.compare_digest(request.headers.get("X-Token", ""), TOKEN):
            abort(403)
        origem = request.headers.get("Origin")
        if origem and origem not in {"http://" + h for h in HOSTS}:
            abort(403)


def _signer():
    if not cofre.existe():
        return None
    pfx, senha = cofre.carregar()
    return assinar.carregar_signer(pfx, senha)


@app.get("/")
def index():
    return render_template_string(HTML, token=TOKEN)


@app.get("/api/certificado")
def cert_info():
    try:
        s = _signer()
    except Exception as e:  # noqa: BLE001
        return jsonify(ok=False, erro=str(e))
    if not s:
        return jsonify(ok=False)
    t = assinar.titular(s)
    return jsonify(ok=True, nome=t.nome, documento=f"{t.rotulo} {t.documento}", emissor=t.emissor,
                   validade=t.validade.strftime("%d/%m/%Y"), vencido=t.validade.timestamp() < time.time())


@app.post("/api/certificado")
def cert_salvar():
    pfx = request.files["pfx"].read()
    senha = request.form["senha"]
    try:
        assinar.carregar_signer(pfx, senha)
    except Exception:  # noqa: BLE001
        return jsonify(ok=False, erro="Senha incorreta ou arquivo inválido"), 400
    cofre.salvar(pfx, senha)
    return jsonify(ok=True)


@app.delete("/api/certificado")
def cert_remover():
    cofre.remover()
    return jsonify(ok=True)


@app.post("/api/preview")
def preview():
    pdf = request.files["pdf"].read()
    pagina = int(request.form.get("pagina", 0))
    with fitz.open(stream=pdf, filetype="pdf") as d:
        n = len(d)
        png = d[min(max(pagina, 0), n - 1)].get_pixmap(dpi=70).tobytes("png")
    r = send_file(io.BytesIO(png), mimetype="image/png")
    r.headers["X-Paginas"] = str(n)
    return r


@app.post("/api/assinar")
def assinar_pdfs():
    s = _signer()
    if not s:
        return jsonify(erro="Cadastre o certificado primeiro"), 400
    x, y = float(request.form["x"]), float(request.form["y"])
    pagina = int(request.form.get("pagina", 0))
    visivel = request.form.get("visivel", "1") == "1"
    SAIDA.mkdir(parents=True, exist_ok=True)
    resultados = []
    for f in request.files.getlist("pdf"):
        base = re.sub(r"[^\w.\- ]", "_", Path(f.filename).stem)
        try:
            dados = f.read()
            with fitz.open(stream=dados, filetype="pdf") as d:
                pg = min(pagina, len(d) - 1)
            destino = SAIDA / f"{base}_assinado.pdf"
            destino.write_bytes(assinar.assinar(s, dados, pg, x, y, visivel))
            resultados.append({"arquivo": destino.name, "ok": True})
        except Exception as e:  # noqa: BLE001
            resultados.append({"arquivo": f.filename, "ok": False, "erro": str(e)})
    return jsonify(pasta=str(SAIDA), resultados=resultados)


@app.post("/api/abrir-pasta")
def abrir_pasta():
    SAIDA.mkdir(parents=True, exist_ok=True)
    os.startfile(SAIDA)  # abre no Explorer (só Windows)
    return jsonify(ok=True)


@app.get("/api/baixar/<nome>")
def baixar(nome):
    return send_file(SAIDA / Path(nome).name, as_attachment=True)


HTML = r"""<!doctype html><html lang="pt-br"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Assinar PDF</title>
<style>
:root{--bg:#f6f7f9;--fg:#1b1f24;--card:#fff;--ac:#1f5078;--mut:#6b7480;--bd:#dde1e6}
@media(prefers-color-scheme:dark){:root{--bg:#14171a;--fg:#e8eaed;--card:#1d2125;--ac:#6aa9d8;--mut:#9aa3ad;--bd:#2d333a}}
body{margin:0;background:var(--bg);color:var(--fg);font:15px system-ui,sans-serif}
main{max-width:860px;margin:0 auto;padding:20px 16px}
h1{font-size:20px;margin:0 0 16px}.card{background:var(--card);border:1px solid var(--bd);border-radius:10px;padding:16px;margin-bottom:14px}
button{background:var(--ac);color:#fff;border:0;border-radius:8px;padding:9px 16px;font:inherit;cursor:pointer}
button.sec{background:transparent;color:var(--ac);border:1px solid var(--ac)}button:disabled{opacity:.5}
input[type=password],input[type=number]{padding:8px;border:1px solid var(--bd);border-radius:8px;background:var(--card);color:var(--fg);font:inherit}
.mut{color:var(--mut);font-size:13px}.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center}
#drop{border:2px dashed var(--bd);border-radius:10px;padding:26px;text-align:center;cursor:pointer}
#drop.on{border-color:var(--ac)}
#wrap{position:relative;display:inline-block;margin-top:10px;line-height:0;cursor:crosshair;border:1px solid var(--bd)}
#wrap img{max-width:100%;max-height:70vh}
#selo{position:absolute;border:2px solid #1f5078;background:rgba(31,80,120,.15);pointer-events:none}
.ok{color:#1d8a4b}.err{color:#c0392b}
</style>
<main>
<h1>Assinar PDF <span class="mut">— A1 local</span></h1>
<div class="card" id="certcard"></div>
<div class="card" id="signcard" hidden>
  <div id="drop">Arraste PDFs aqui ou clique para escolher (um ou vários)</div>
  <input id="files" type="file" accept="application/pdf" multiple hidden>
  <div id="lista" class="mut" style="margin-top:8px"></div>
  <div id="prev" hidden>
    <div class="row" style="margin-top:10px">
      <label>Página <input id="pg" type="number" min="1" value="1" style="width:70px"></label>
      <label><input id="vis" type="checkbox" checked> Selo visível</label>
      <span class="mut">Clique na página para posicionar o selo (vale para todos os PDFs).</span>
    </div>
    <div id="wrap"><img id="img"><div id="selo"></div></div>
  </div>
  <div class="row" style="margin-top:12px"><button id="go" disabled>ASSINAR</button><button class="sec" id="pasta">Abrir pasta dos assinados</button><span id="st"></span></div>
  <div id="res" style="margin-top:10px"></div>
</div>
</main>
<script>
const $=id=>document.getElementById(id);const T='{{token}}';
const api=(u,o={})=>fetch(u,{...o,headers:{'X-Token':T}});let arquivos=[],pos={x:.55,y:.88};
try{pos=JSON.parse(localStorage.pos)||pos}catch(e){}
async function cert(){
  const r=await (await api('/api/certificado')).json(),c=$('certcard');const E=t=>String(t).replace(/[&<>]/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[m]));
  if(r.ok){c.innerHTML=`<b>${E(r.nome)}</b> · ${E(r.documento)}<br><span class="mut">${E(r.emissor)} · validade ${E(r.validade)}</span>
   ${r.vencido?'<div class="err">Certificado vencido!</div>':''}<div style="margin-top:8px"><button class="sec" id="trocar">Trocar certificado</button></div>`;
   $('signcard').hidden=false;$('trocar').onclick=()=>form(c)}
  else{form(c);if(r.erro)$('cerr').textContent=r.erro;$('signcard').hidden=true}
}
function form(c){c.innerHTML=`<b>Certificado A1</b> <span class="mut">(só precisa fazer uma vez; fica salvo neste PC)</span>
 <div class="row" style="margin-top:8px"><input type="file" id="pfx" accept=".pfx,.p12"><input type="password" id="pw" placeholder="Senha do certificado"><button id="salvar">Salvar</button></div><div id="cerr" class="err"></div>`;
 $('salvar').onclick=async()=>{const f=new FormData();f.append('pfx',$('pfx').files[0]);f.append('senha',$('pw').value);
  const r=await api('/api/certificado',{method:'POST',body:f});if(r.ok)cert();else $('cerr').textContent=(await r.json()).erro}}
$('drop').onclick=()=>$('files').click();
$('drop').ondragover=e=>{e.preventDefault();$('drop').classList.add('on')};
$('drop').ondragleave=()=>$('drop').classList.remove('on');
$('drop').ondrop=e=>{e.preventDefault();$('drop').classList.remove('on');set([...e.dataTransfer.files].filter(f=>/pdf$/i.test(f.name)))};
$('files').onchange=e=>set([...e.target.files]);
function set(l){arquivos=l;$('lista').textContent=l.map(f=>f.name).join(', ');$('go').disabled=!l.length;$('prev').hidden=!l.length;$('res').innerHTML='';if(l.length)preview()}
async function preview(){const f=new FormData();f.append('pdf',arquivos[0]);f.append('pagina',$('pg').value-1);
  const r=await api('/api/preview',{method:'POST',body:f});$('pg').max=r.headers.get('X-Paginas');
  $('img').onload=selo;$('img').src=URL.createObjectURL(await r.blob())}
$('pg').onchange=preview;
function selo(){const i=$('img'),s=$('selo');s.style.left=pos.x*i.width+'px';s.style.top=pos.y*i.height+'px';
  s.style.width=Math.min(230/595*i.width,i.width)+'px';s.style.height=60/842*i.height+'px';s.style.display=$('vis').checked?'block':'none'}
$('vis').onchange=selo;
$('img').onclick=e=>{const r=$('img').getBoundingClientRect();pos={x:Math.min((e.clientX-r.left)/r.width,.99),y:Math.min((e.clientY-r.top)/r.height,.99)};
  try{localStorage.pos=JSON.stringify(pos)}catch(e){}selo()};
$('go').onclick=async()=>{$('go').disabled=true;$('st').textContent='Assinando…';
  const f=new FormData();arquivos.forEach(a=>f.append('pdf',a));f.append('x',pos.x);f.append('y',pos.y);f.append('pagina',$('pg').value-1);f.append('visivel',$('vis').checked?1:0);
  const r=await (await api('/api/assinar',{method:'POST',body:f})).json();$('st').textContent='';$('go').disabled=false;
  if(r.erro){$('res').innerHTML=`<span class=err>${r.erro}</span>`;return}
  $('res').innerHTML=r.resultados.map(x=>x.ok?`<div class=ok>✔ ${x.arquivo.replace(/</g,'&lt;')}</div>`:`<div class=err>✘ ${(x.arquivo+': '+x.erro).replace(/</g,'&lt;')}</div>`).join('')+`<div class=mut>Salvos em ${r.pasta}</div>`};
$('pasta').onclick=()=>api('/api/abrir-pasta',{method:'POST'});
cert();
</script></html>"""

if __name__ == "__main__":
    print("Assinador rodando em http://127.0.0.1:5055  (Ctrl+C para fechar)")
    app.run(host="127.0.0.1", port=5055)
