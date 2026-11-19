# StarCat Launcher

Launcher não oficial para **Minecraft Bedrock Edition (Android, ARM64)**. Importa o APK da **sua própria cópia**
do jogo e o executa dentro do launcher, em instâncias isoladas, sem abrir o app oficial instalado.

- Várias versões do jogo lado a lado, cada uma com seus mundos, packs e configurações
- Carregamento de mods nativos (`.so`) e pacotes, por sua conta e risco
- Gerenciador de mundos e resource packs, com backup (`Download/StarCatLauncher/Backups`)
- Sem telemetria: relatórios de falha ficam só no aparelho

## Requisitos
Android 9+ (API 28), ARM64, Minecraft Bedrock comprado na Google Play (necessário para validar a licença).

## Como compilar
1. Rode `bash scripts/fetch-native-deps.sh` (baixa `preloader` e `libHttpClient`, que vêm vazios no código-fonte).
2. JDK 21, Android SDK (build-tools 35), NDK r28c e Xmake 2.9.9 instalados.
3. `./gradlew assembleDebug`

No GitHub, o workflow **Android CI** faz tudo isso e publica o APK como artefato.

## libgxcore.so
`app/src/main/cpp/gxcore` é a nossa implementação aberta do `libgxcore.so` (o binário fechado do LeviLauncher
não é usado). Ela cobre as 4 funções nativas de `NativeBridgeHelper`: verificar/preparar bibliotecas (valida o ELF e
copia), bootstrap (nada a fazer) e carregar o runtime do Minecraft (lê o `DT_NEEDED` e faz `dlopen` das dependências
na ordem). Não há ligação com o Discord.

## Termos
Aparecem na primeira abertura (`eula_*` em `res/values*/strings.xml`).

## Créditos e licença
Baseado no [LeviLauncher-Android](https://github.com/LiteLDev/LeviLauncher-Android) (LiteLDev), Apache 2.0.
Veja `LICENSE` e `NOTICE`. Não afiliado à Mojang, Microsoft ou Xbox.
