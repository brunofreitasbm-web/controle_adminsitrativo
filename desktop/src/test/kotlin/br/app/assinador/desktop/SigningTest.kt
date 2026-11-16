package br.app.assinador.desktop

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date

class SigningTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Made(val signer: SignerKey, val pfx: File)

    private fun made(password: String = "senha"): Made {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=JOAO DA SILVA:12345678901,O=ICP-Brasil,C=BR")
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(0), Date(System.currentTimeMillis() + 86_400_000L), name, kp.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
        val cert = JcaX509CertificateConverter().setProvider(BouncyCastleProvider()).getCertificate(holder)
        val pfx = tmp.newFile("cert.pfx")
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("a", kp.private, password.toCharArray(), arrayOf(cert))
            pfx.outputStream().use { store(it, password.toCharArray()) }
        }
        return Made(SignerKey(kp.private, listOf(cert)), pfx)
    }

    private fun blankPdf(pages: Int, name: String = "doc.pdf"): File {
        val f = File(tmp.root, name)
        PDDocument().use { d -> repeat(pages) { d.addPage(PDPage()) }; d.save(f) }
        return f
    }

    @Test fun signsAndVerifiesVisible() {
        val out = tmp.newFile()
        PadesSigner.sign(made().signer, blankPdf(2), out, StampSpec(1, 0.1f, 0.8f, true))
        System.getenv("SIGNED_COPY")?.let { out.copyTo(File(it), true) }
        val r = SignatureVerifier.verify(out)
        assertEquals(1, r.size)
        assertEquals(SigStatus.VALID, r[0].status)
        assertEquals("JOAO DA SILVA", r[0].signer)
    }

    @Test fun lastPageWhenIndexNegative() {
        val out = tmp.newFile()
        PadesSigner.sign(made().signer, blankPdf(3), out, StampSpec(-1, 0.1f, 0.8f, true))
        PDDocument.load(out).use { d ->
            assertEquals(1, d.getPage(2).annotations.size)
            assertEquals(0, d.getPage(0).annotations.size)
        }
    }

    @Test fun signsInvisibleAndSecondSignatureKeepsFirstValid() {
        val signer = made().signer
        val a = tmp.newFile(); val b = tmp.newFile()
        PadesSigner.sign(signer, blankPdf(1), a, StampSpec(0, 0f, 0f, false))
        PadesSigner.sign(signer, a, b, StampSpec(0, 0.1f, 0.1f, true))
        val r = SignatureVerifier.verify(b)
        assertEquals(2, r.size)
        assertTrue(r.all { it.status != SigStatus.INVALID })
    }

    @Test fun tamperingIsDetected() {
        val out = tmp.newFile()
        PadesSigner.sign(made().signer, blankPdf(1), out, StampSpec(0, 0.1f, 0.8f, true))
        val bytes = out.readBytes()
        val i = String(bytes, Charsets.ISO_8859_1).indexOf("/Type /Catalog").takeIf { it >= 0 } ?: 20
        bytes[i] = (bytes[i] + 1).toByte()
        out.writeBytes(bytes)
        assertTrue(SignatureVerifier.verify(out).any { it.status == SigStatus.INVALID })
    }

    @Test fun signatureCarriesAdRbPolicy() {
        val out = tmp.newFile()
        PadesSigner.sign(made().signer, blankPdf(1), out, StampSpec(0, 0.1f, 0.8f, true))
        val bytes = out.readBytes()
        PDDocument.load(out).use { doc ->
            val sig = doc.signatureDictionaries.first()
            val cms = org.bouncycastle.cms.CMSSignedData(
                org.bouncycastle.cms.CMSProcessableByteArray(sig.getSignedContent(bytes)), sig.getContents(bytes))
            val attrs = cms.signerInfos.signers.first().signedAttributes
            val policy = attrs.get(org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.id_aa_ets_sigPolicyId)
            assertTrue(policy != null)
            val id = org.bouncycastle.asn1.esf.SignaturePolicyId.getInstance(policy.attrValues.getObjectAt(0))
            assertEquals("2.16.76.1.7.1.11.1.3", id.sigPolicyId.id)
            assertNull(attrs.get(org.bouncycastle.asn1.cms.CMSAttributes.signingTime))
        }
    }

    @Test fun loadsPfxAndRejectsWrongPassword() {
        val m = made("correta")
        val signer = CertificateStore.load(m.pfx, "correta".toCharArray())
        assertEquals("JOAO DA SILVA", CertificateStore.infoOf(signer.certificate).name)
        try {
            CertificateStore.load(m.pfx, "errada".toCharArray())
            throw AssertionError("deveria falhar")
        } catch (e: WrongPasswordException) { /* ok */ }
    }

    @Test fun batchWritesToAssinadosAndNeverOverwrites() {
        val signer = made().signer
        val pdf = blankPdf(1, "contrato.pdf")
        val r1 = BatchSigner.run(signer, listOf(pdf), StampSpec(-1, 0.06f, 0.88f))
        val r2 = BatchSigner.run(signer, listOf(pdf), StampSpec(-1, 0.06f, 0.88f))
        assertEquals(File(tmp.root, "Assinados/contrato_assinado.pdf").canonicalPath, r1[0].output!!.canonicalPath)
        assertEquals("contrato_assinado (2).pdf", r2[0].output!!.name)
        val bad = File(tmp.root, "quebrado.pdf").apply { writeText("não é pdf") }
        val r3 = BatchSigner.run(signer, listOf(bad, pdf), StampSpec(-1, 0.06f, 0.88f))
        assertTrue(r3[0].error != null && r3[1].error == null)
    }
}
