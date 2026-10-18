package br.app.assinador.cert

import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import org.spongycastle.asn1.x500.style.BCStyle
import org.spongycastle.cert.jcajce.JcaX509CertificateHolder
import java.io.InputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date

/** Dados do titular extraídos do certificado A1 (CN ICP-Brasil: "NOME:DOCUMENTO"). */
data class CertInfo(val name: String, val document: String, val notAfter: Date, val issuer: String) {
    val formattedDocument: String
        get() = when (document.length) {
            11 -> "${document.substring(0, 3)}.${document.substring(3, 6)}.${document.substring(6, 9)}-${document.substring(9)}"
            14 -> "${document.substring(0, 2)}.${document.substring(2, 5)}.${document.substring(5, 8)}/${document.substring(8, 12)}-${document.substring(12)}"
            else -> document
        }
    val documentLabel: String get() = if (document.length == 14) "CNPJ" else "CPF"
}

class SignerKey(val key: PrivateKey, val chain: List<X509Certificate>) {
    val certificate: X509Certificate get() = chain.first()
    val signatureAlgorithm: String
        get() = if (key.algorithm.equals("EC", true) || key.algorithm.equals("ECDSA", true)) "SHA256withECDSA" else "SHA256withRSA"
}

class WrongPasswordException : Exception("Senha do certificado incorreta")

/**
 * Guarda o certificado A1 no AndroidKeyStore. A chave privada importada não é exportável
 * e o app não grava a senha do .pfx.
 */
object CertificateStore {
    private const val ALIAS = "a1-signer"
    private val androidKs: KeyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun hasCertificate(): Boolean = androidKs.containsAlias(ALIAS)

    fun remove() {
        val ks = androidKs
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
    }

    fun import(pfx: InputStream, password: CharArray): CertInfo {
        val p12 = KeyStore.getInstance("PKCS12")
        try {
            p12.load(pfx, password)
        } catch (e: java.io.IOException) {
            // PKCS12 com senha errada falha com IOException ("keystore password was incorrect")
            throw WrongPasswordException()
        }
        val alias = p12.aliases().toList().firstOrNull { p12.isKeyEntry(it) }
            ?: throw IllegalArgumentException("O arquivo não contém chave privada")
        val key = p12.getKey(alias, password) as PrivateKey
        val chain = p12.getCertificateChain(alias).map { it as X509Certificate }.toTypedArray()

        val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .build()
        androidKs.setEntry(ALIAS, KeyStore.PrivateKeyEntry(key, chain), protection)
        password.fill('0')
        return infoOf(chain.first())
    }

    fun load(): SignerKey? {
        val ks = androidKs
        if (!ks.containsAlias(ALIAS)) return null
        val key = ks.getKey(ALIAS, null) as? PrivateKey ?: return null
        val chain = ks.getCertificateChain(ALIAS)?.map { it as X509Certificate } ?: return null
        return SignerKey(key, chain)
    }

    fun info(): CertInfo? = load()?.let { infoOf(it.certificate) }

    fun infoOf(cert: X509Certificate): CertInfo {
        val holder = JcaX509CertificateHolder(cert)
        val cn = holder.subject.getRDNs(BCStyle.CN).firstOrNull()?.first?.value?.toString() ?: cert.subjectX500Principal.name
        val issuerCn = holder.issuer.getRDNs(BCStyle.CN).firstOrNull()?.first?.value?.toString().orEmpty()
        val name = cn.substringBefore(':').trim()
        val doc = cn.substringAfter(':', "").filter { it.isDigit() }
        return CertInfo(name, doc, cert.notAfter, issuerCn)
    }
}
