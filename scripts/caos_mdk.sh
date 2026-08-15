#!/usr/bin/env bash
# Roda a suíte de caos do MockDeviceKit — **um método por processo**.
#
# Necessário porque `MockDeviceKit.getInstance` é singleton de processo e o
# decodificador de vídeo não volta ao estado limpo entre habilitações: os testes
# que abrem stream passam isolados e devolvem zero frames quando rodam em lote.
# Medido, não suposto.
set -uo pipefail
cd "$(dirname "$0")/.."

CLASSE="${1:-com.claryon.field.CaosDoDatTest}"
ARQ=$(find app/src/androidTest -name "$(basename "${CLASSE//./\/}").kt" | head -1)
[ -z "$ARQ" ] && { echo "classe não encontrada: $CLASSE"; exit 1; }

METODOS=$(grep -A1 '^\s*@Test' "$ARQ" | grep -oE 'fun [a-zA-Z0-9_]+' | sed 's/fun //')
TOTAL=0; VERDES=0; FALHAS=()

for m in $METODOS; do
  TOTAL=$((TOTAL+1))
  printf '  %-58s ' "$m"
  if ./gradlew :app:connectedDebugAndroidTest \
       -Pandroid.testInstrumentationRunnerArguments.class="$CLASSE#$m" \
       >/dev/null 2>&1; then
    echo "ok"; VERDES=$((VERDES+1))
  else
    echo "FALHOU"; FALHAS+=("$m")
  fi
done

echo
echo "$VERDES/$TOTAL verdes"
[ ${#FALHAS[@]} -gt 0 ] && { printf 'falharam: %s\n' "${FALHAS[*]}"; exit 1; }
exit 0
