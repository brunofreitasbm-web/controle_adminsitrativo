# Assinar PDF (Android, certificado A1)

App pessoal para assinar PDFs com certificado A1 (.pfx/.p12) direto no celular.

## Uso
1. **Primeira vez:** abra o app → *Escolher certificado A1* → senha. (Uma vez só; a senha não é guardada.)
2. Em qualquer app (WhatsApp, e-mail, Arquivos): **Compartilhar → Assinar PDF** (um ou vários PDFs).
3. Arraste o selo se quiser (a posição fica salva) → **ASSINAR**.
4. Saída: `Download/Assinados/<nome>_assinado.pdf` + botão **Compartilhar**.

Extras: aviso de vencimento do certificado, assinatura em lote, *Verificar assinaturas* de um PDF recebido.

## Instalar
O APK é gerado pelo GitHub Actions (aba **Actions → Build APK → artefato `assinador-apk`**). O APK usa uma
chave fixa (`app/debug.keystore`), então novas versões instalam por cima sem apagar o certificado importado.
Ative "instalar apps desconhecidos" para o navegador/Arquivos.

## Limites conhecidos
- Gera **PAdES (CAdES-detached, SHA-256, cadeia embutida, ESS signing-certificate-v2)**. Ainda **não** inclui
  `SignaturePolicyIdentifier` (política AD-RB) nem carimbo de tempo; por isso o Verificador do ITI pode
  validar a assinatura mas não classificá-la como "AD-RB". Falta confirmar o OID e o hash oficial da política.
- Não há trava por biometria/PIN: quem desbloquear o celular pode assinar. Mantenha o bloqueio de tela.
- Páginas com `/Rotate` não têm o posicionamento do selo tratado.
- A chave importada não pode ser exportada: guarde o .pfx original.

## Desenvolvimento
`./gradlew :app:testDebugUnitTest :app:assembleDebug` (precisa de Android SDK 34 e JDK 17+).
