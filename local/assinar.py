"""Assinatura PAdES AD-RB v1.3 (ICP-Brasil) com certificado A1, usando pyHanko."""
import io
import re
from dataclasses import dataclass
from datetime import datetime

from asn1crypto import algos, core
from pyhanko.pdf_utils.incremental_writer import IncrementalPdfFileWriter
from pyhanko.pdf_utils.reader import PdfFileReader
from pyhanko.sign import fields, signers
from pyhanko.sign.ades.api import CAdESSignedAttrSpec
from pyhanko.sign.ades.cades_asn1 import (
    SigPolicyQualifierInfo, SigPolicyQualifierInfos, SignaturePolicyId, SignaturePolicyIdentifier,
)
from pyhanko.sign.fields import SigFieldSpec
from pyhanko.stamp import TextStampStyle

# Política AD-RB v1.3 da LPA do ITI (mesmos valores do app Android).
POLITICA_OID = "2.16.76.1.7.1.11.1.3"
POLITICA_URI = "http://politicas.icpbrasil.gov.br/PA_PAdES_AD_RB_v1_3.der"
POLITICA_SHA256 = bytes.fromhex("23da544aef71f7a75dc85fa6e17a83875741e4baef41ec178258a5c86ace54dd")

LARGURA, ALTURA = 230, 60  # pontos


@dataclass
class Titular:
    nome: str
    documento: str
    rotulo: str
    validade: datetime
    emissor: str


def titular(signer: signers.SimpleSigner) -> Titular:
    cert = signer.signing_cert
    cn = cert.subject.native.get("common_name", "")
    cn = cn[0] if isinstance(cn, list) else cn
    nome, _, resto = cn.partition(":")
    doc = re.sub(r"\D", "", resto)
    fmt = doc
    if len(doc) == 11:
        fmt = f"{doc[:3]}.{doc[3:6]}.{doc[6:9]}-{doc[9:]}"
    elif len(doc) == 14:
        fmt = f"{doc[:2]}.{doc[2:5]}.{doc[5:8]}/{doc[8:12]}-{doc[12:]}"
    emissor = cert.issuer.native.get("common_name", "")
    emissor = emissor[0] if isinstance(emissor, list) else emissor
    return Titular(nome.strip(), fmt, "CNPJ" if len(doc) == 14 else "CPF", cert.not_valid_after, emissor)


def carregar_signer(pfx: bytes, senha: str) -> signers.SimpleSigner:
    s = signers.SimpleSigner.load_pkcs12_data(pfx, other_certs=None, passphrase=senha.encode("utf-8"))
    if s is None:
        raise ValueError("Arquivo sem chave privada")
    return s


def _politica() -> CAdESSignedAttrSpec:
    pid = SignaturePolicyId({
        "sig_policy_id": POLITICA_OID,
        "sig_policy_hash": algos.DigestInfo({
            "digest_algorithm": algos.DigestAlgorithm({"algorithm": "sha256"}),
            "digest": POLITICA_SHA256,
        }),
        "sig_policy_qualifiers": SigPolicyQualifierInfos([
            SigPolicyQualifierInfo({"sig_policy_qualifier_id": "sp_uri", "sig_qualifier": core.IA5String(POLITICA_URI)})
        ]),
    })
    return CAdESSignedAttrSpec(signature_policy_identifier=SignaturePolicyIdentifier(("signature_policy_id", pid)))


def assinar(signer: signers.SimpleSigner, pdf: bytes, pagina: int, x: float, y: float, visivel: bool = True) -> bytes:
    """x, y: fração (0..1) do canto superior esquerdo do selo em relação à página."""
    t = titular(signer)
    w = IncrementalPdfFileWriter(io.BytesIO(pdf), strict=False)
    meta = signers.PdfSignatureMetadata(
        field_name="Assinatura1", md_algorithm="sha256", subfilter=fields.SigSeedSubFilter.PADES,
        cades_signed_attr_spec=_politica(),
    )
    ps = signers.PdfSigner(meta, signer=signer)
    if visivel:
        x0, y0, x1, y1 = _mediabox(pdf, pagina)
        pw, ph = x1 - x0, y1 - y0
        w_, h_ = min(LARGURA, pw), min(ALTURA, ph)
        esq = x0 + min(max(x * pw, 0), pw - w_)
        topo = y1 - min(max(y * ph, 0), ph - h_)
        data = datetime.now().astimezone().strftime("%d/%m/%Y %H:%M:%S %z")
        linhas = ["Assinado digitalmente por:", t.nome, f"{t.rotulo}: {t.documento}" if t.documento else "", f"Data: {data}"]
        texto = "\n".join(l.replace("%", "%%") for l in linhas if l)
        ps = signers.PdfSigner(
            meta, signer=signer,
            stamp_style=TextStampStyle(stamp_text=texto, border_width=1),
            new_field_spec=SigFieldSpec("Assinatura1", on_page=pagina, box=(esq, topo - h_, esq + w_, topo)),
        )
    out = io.BytesIO()
    ps.sign_pdf(w, output=out)
    return out.getvalue()


def _mediabox(pdf: bytes, pagina: int):
    import pymupdf as fitz
    with fitz.open(stream=pdf, filetype="pdf") as d:
        p = d[pagina]
        r = p.mediabox
        return (r.x0, r.y0, r.x1, r.y1)
