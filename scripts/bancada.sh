#!/usr/bin/env bash
#
# Bancada de medição no emulador — instala, prepara o estado e MEDE.
#
# Existe por causa de um erro concreto: três rodadas de medição saíram vazias e
# eu quase reportei como regressão do próprio diff. A causa não era o código —
# `connectedAndroidTest` reinstala o APK, e a reinstalação **revoga as
# permissões de runtime e apaga a sessão do cofre cifrado**. O app ficava parado
# na tela de permissões, os `input tap` iam para o vazio, e o relatório saía sem
# amostras. Medição vazia é indistinguível de medição ruim se ninguém confere a
# pré-condição.
#
# Por isso este script **aborta com mensagem** em vez de medir o nada: antes de
# tocar no PTT ele exige ver o rádio aberto no log. É a mesma regra do produto —
# falha nunca é silêncio — aplicada à ferramenta que mede o produto.
#
# Mede DOIS cenários, e a diferença entre eles é informação:
#
#   limpo      PTT sozinho — a latência que o agente vê no uso normal.
#   concorrente PTT com o ciclo de voz rodando junto. O ciclo carrega o modelo
#              do whisper (~75 MB) e disputa CPU; medido no emulador, a latência
#              do PTT sobe de ~100 ms para ~270 ms. Não é defeito escondido: é o
#              custo real de pedir ao copiloto e falar no rádio ao mesmo tempo,
#              e precisa aparecer em vez de sumir numa média.
#
#   ./scripts/bancada.sh [rodadas]     # padrão: 3
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

APP=com.claryon.field
RODADAS="${1:-3}"

# Coordenadas em px do AVD `claryon` (1080×2400). Se o AVD mudar, mudam aqui.
readonly TAP_SEGUIR_SEM_ENTRAR="540 1658"
readonly TAP_PTT="540 2066"
readonly TAP_COPILOTO="250 1810"

abortar() { echo "✗ $*" >&2; exit 1; }

adb get-state >/dev/null 2>&1 || abortar "nenhum emulador/aparelho conectado (adb get-state)"

echo "▸ instalando"
./gradlew --offline :app:installDebug -q >/dev/null || abortar "installDebug falhou"

echo "▸ concedendo permissões (a reinstalação as revoga)"
for p in RECORD_AUDIO ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION \
         POST_NOTIFICATIONS CAMERA BLUETOOTH_CONNECT; do
  adb shell pm grant "$APP" "android.permission.$p" >/dev/null 2>&1 || true
done

# Espera o rádio abrir, ou desiste dizendo por quê. É esta função que impede a
# medição vazia silenciosa.
esperar_radio() {
  for _ in $(seq 1 25); do
    if adb logcat -d -s ClaryonField 2>/dev/null | grep -q "Áudio roteado"; then
      return 0
    fi
    sleep 1
  done
  return 1
}

for r in $(seq 1 "$RODADAS"); do
  adb shell am force-stop "$APP" >/dev/null
  adb logcat -c
  adb shell am start -n "$APP/.MainActivity" >/dev/null 2>&1
  sleep 8

  # A reinstalação também apaga a sessão: o app cai no portão de login. "Seguir
  # sem entrar" é inofensivo se a tela não estiver lá (o tap cai no vazio).
  adb shell input tap $TAP_SEGUIR_SEM_ENTRAR >/dev/null 2>&1 || true

  if ! esperar_radio; then
    adb shell screencap -p /sdcard/bancada-erro.png >/dev/null 2>&1
    adb pull /sdcard/bancada-erro.png /tmp/bancada-erro.png >/dev/null 2>&1 || true
    abortar "o rádio não abriu em 25 s — o app provavelmente está em permissões ou login.
   Tela salva em /tmp/bancada-erro.png. NÃO existe medição válida nesta rodada."
  fi

  if [ "${CENARIO:-limpo}" = "concorrente" ]; then
    adb shell input tap $TAP_COPILOTO >/dev/null 2>&1 || true
    sleep 1
  fi
  # Vários toques por rodada, e não um: com n=1 o relatório mostra um valor
  # cru, e o emulador tem ruído de dezenas de milissegundos entre execuções.
  # p50/p95 sobre n≥6 é o que separa "a meta foi atingida" de "deu sorte".
  for _ in $(seq 1 "${TOQUES:-6}"); do
    adb shell input swipe $TAP_PTT $TAP_PTT 1500; sleep 2
  done
  adb shell input keyevent KEYCODE_BACK; sleep 3

  echo
  echo "═══ rodada $r/$RODADAS · cenário ${CENARIO:-limpo} ═══"
  adb logcat -d -s ClaryonField \
    | grep -E "toque ate primeiro quadro|concessao de canal|codificacao:|agrupamento:|P1 corta" \
    | sed 's/.*ClaryonField: //'
  echo "  AudioRecord aberto : $(adb logcat -d -s ClaryonField | grep -c 'AudioRecord aberto')"
  echo "  crash              : $(adb logcat -d | grep -c 'FATAL EXCEPTION')"
  echo "  StrictMode (nosso) : $(adb logcat -d | grep 'at com\.claryon' | sed 's/.*at /at /' | sort -u | wc -l | tr -d ' ')"
done
