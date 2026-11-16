"""Teste ponta a ponta com um certificado de mentira (não usa o seu A1)."""
import datetime
import io
import tempfile
from pathlib import Path

import pymupdf as fitz
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from pyhanko.pdf_utils.reader import PdfFileReader
from pyhanko.sign.validation import validate_pdf_signature

import assinar
import cofre

k = rsa.generate_private_key(65537, 2048)
n = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "JOAO DA SILVA:12345678909")])
c = (x509.CertificateBuilder().subject_name(n).issuer_name(n).public_key(k.public_key()).serial_number(1)
     .not_valid_before(datetime.datetime(2025, 1, 1)).not_valid_after(datetime.datetime(2030, 1, 1)).sign(k, hashes.SHA256()))
pfx = serialization.pkcs12.serialize_key_and_certificates(b"t", k, c, None, serialization.BestAvailableEncryption(b"123"))

tmp = Path(tempfile.mkdtemp())
cofre.DADOS, cofre.PFX, cofre.SENHA = tmp, tmp / "c.pfx", tmp / "s.bin"
cofre.salvar(pfx, "minhasenha")
assert cofre.carregar()[1] == "minhasenha"
print("senha cifrada em disco:", cofre.SENHA.read_bytes() != b"minhasenha")

d = fitz.open(); d.new_page(); d.new_page()
sg = assinar.carregar_signer(pfx, "123")
print(assinar.titular(sg))
for vis in (True, False):
    out = assinar.assinar(sg, d.tobytes(), 1, .5, .8, vis)
    sig = PdfFileReader(io.BytesIO(out)).embedded_signatures[0]
    st = validate_pdf_signature(sig)
    print("visivel", vis, "| integra:", st.intact, "| PAdES:", sig.sig_object["/SubFilter"],
          "| política ICP-Brasil:", bytes([0x60,0x4c,1,7,1,0x0b,1,3]) in sig.pkcs7_content)
(tmp / "saida.pdf").write_bytes(out)
print("pdf em", tmp / "saida.pdf")
