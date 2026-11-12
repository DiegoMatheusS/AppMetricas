# APK assinado e atualização sem desinstalar

O workflow `Signed release APK` gera um APK release assinado automaticamente após o **Android CI** aprovar uma atualização da `main`, incluindo os testes de unidade e do emulador. O APK usa exatamente o commit validado. A execução manual na aba **Actions** continua disponível.

## 1. Criar a chave privada (uma única vez)
Na sua máquina, com o JDK instalado:

```bash
keytool -genkeypair -v -keystore appmetricas-release.jks -alias appmetricas -keyalg RSA -keysize 3072 -validity 10000
```

Guarde o arquivo `.jks`, a senha do arquivo, o alias e a senha da chave em backup **fora do GitHub e fora do código**. Nunca compartilhe esses valores no chat, em commits, issues ou PRs.

## 2. Configurar secrets em GitHub > Settings > Secrets and variables > Actions

- `APPMETRICAS_KEYSTORE_BASE64`: conteúdo base64 de `appmetricas-release.jks`.
- `APPMETRICAS_KEYSTORE_PASSWORD`: senha do arquivo.
- `APPMETRICAS_KEY_ALIAS`: alias (ex.: `appmetricas`).
- `APPMETRICAS_KEY_PASSWORD`: senha da chave privada.

Converter o arquivo para base64 em Linux:

```bash
base64 -w 0 appmetricas-release.jks > appmetricas-release.base64.txt
```

PowerShell no Windows:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("appmetricas-release.jks")) | Set-Content -NoNewline appmetricas-release.base64.txt
```

Copie o conteúdo do arquivo base64 como secret e **exclua o arquivo base64 temporário**.

## 3. Gerar APK

Após um merge aprovado na `main`, aguarde o **Android CI** e depois o **Signed release APK**. Baixe o artifact `appmetricas-signed-release-<numero>`. O número de versão aumenta por execução do workflow.

Para gerar novamente de forma manual, abra **Actions > Signed release APK > Run workflow**, selecione `main` e execute.

## Regras para atualizar um APK instalado

- Mesmo `applicationId` e **mesmo certificado de assinatura** em toda atualização.
- `versionCode` novo precisa ser maior do que o instalado.
- Se o app anterior foi instalado como **debug** ou assinado com **outra chave**, o Android **não permitirá** atualizá-lo por cima. Será necessário desinstalar **essa única vez**, ou usar exatamente a chave antiga. Faça backup dos dados antes.
- Nunca gere uma nova chave para as próximas versões; perder a chave impede atualizações diretas.
- O pipeline CI normal continua usando `assembleDebug` sem secrets. Os arquivos privados não são enviados ao repositório.
