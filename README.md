# Assinar PDF (Android e Windows, certificado A1)

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
- Gera **PAdES AD-RB v1.3** (CAdES-detached, SHA-256, cadeia embutida, `signingCertificateV2` e `sigPolicyId`
  `2.16.76.1.7.1.11.1.3`, com o hash do arquivo oficial da LPA do ITI). Não há carimbo de tempo (isso seria AD-RT).
  A conformidade com o Verificador do ITI ainda precisa ser confirmada com um A1 real: o teste automático só prova que
  o atributo da política está presente e que o PDF valida no `pdfsig`.
- Não há trava por biometria/PIN: quem desbloquear o celular pode assinar. Mantenha o bloqueio de tela.
- Páginas com `/Rotate` não têm o posicionamento do selo tratado.
- A chave importada não pode ser exportada: guarde o .pfx original.

## Desenvolvimento
`./gradlew :app:testDebugUnitTest :app:assembleDebug` (precisa de Android SDK 34 e JDK 17+).

## Windows (.exe)
Versão para PC em `desktop/` (Kotlin + Swing, mesmo motor de assinatura PAdES AD-RB do app Android).

**Instalar:** GitHub → aba **Actions → Build Windows (.exe)** → artefato `assinador-windows`:
- `Assinar PDF-1.0.0.exe`: instalador. Cria o atalho na **Área de Trabalho** e no Menu Iniciar. Não precisa ter Java instalado.
- `Assinar-PDF-portatil.zip`: sem instalar. Extraia, rode `Criar-atalho-na-area-de-trabalho.bat` e use o atalho.

O Windows SmartScreen pode avisar "editor desconhecido", porque o .exe não tem assinatura de código. Clique em *Mais informações → Executar assim mesmo*.

**Uso:** *Escolher certificado A1* (.pfx, senha só em memória; o caminho do arquivo fica lembrado, a senha é pedida a cada abertura) →
arraste os PDFs para a janela → arraste o selo na prévia → **ASSINAR**. Saída: pasta `Assinados` ao lado de cada PDF
(`<nome>_assinado.pdf`, nunca sobrescreve). Também verifica a integridade das assinaturas de um PDF.

Limites: só certificado em arquivo (.pfx/.p12); A3/token e o repositório de certificados do Windows não são suportados. Mesmas ressalvas
de conformidade ICP-Brasil e de `/Rotate` do app Android.

Desenvolvimento: `cd desktop && ./gradlew test run` (JDK 17+).
