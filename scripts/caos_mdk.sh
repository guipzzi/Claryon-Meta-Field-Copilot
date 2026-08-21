#!/usr/bin/env bash
# Roda a suíte de caos do MockDeviceKit — **um método por processo**.
#
# Necessário porque `MockDeviceKit.getInstance` é singleton de processo e o
# decodificador de vídeo não volta ao estado limpo entre habilitações: os testes
# que abrem stream passam isolados e devolvem zero frames quando rodam em lote.
# Medido, não suposto.
#
# ## O defeito que este script TINHA, e por que o conserto é o assunto
#
# Ele decidia por `if ./gradlew ...`, isto é, pelo código de saída. E o Gradle
# devolve ZERO quando o runner não executou nada: uma classe com `@Ignore` no
# nível da classe produz `tests="0"` no XML e sai com sucesso. O script imprimia
# "ok" por método e fechava com **"12/12 verdes" sem ter rodado uma linha** —
# sobre a `CaosDoAparelhoTest`, que é exatamente o caso.
#
# Agora o veredito vem do XML de resultado, não do código de saída. `tests="0"`
# é VAZIO, `skipped>0` é PULADO, e os dois contam como falha. Verde aqui passou
# a significar "executou e passou".
set -uo pipefail
cd "$(dirname "$0")/.."

CLASSE="${1:-com.claryon.field.CaosDoDatTest}"
ARQ=$(find app/src/androidTest -name "$(basename "${CLASSE//./\/}").kt" | head -1)
[ -z "$ARQ" ] && { echo "classe não encontrada: $CLASSE"; exit 1; }

RESULTADOS=app/build/outputs/androidTest-results/connected/debug

# Um `@Ignore` no nível da classe faz TODO método devolver tests="0". Dizer isso
# antes de gastar uma rodada por método é mais barato que descobrir no fim.
if grep -qE '^\s*@(org\.junit\.)?Ignore' "$ARQ" &&
   ! grep -qE '^\s*@(org\.junit\.)?Ignore' <(grep -A2 '^\s*@Test' "$ARQ"); then
  echo "AVISO: $ARQ tem @Ignore no nível da classe."
  echo "       O runner devolverá tests=\"0\" para todo método e este script"
  echo "       reprovará cada um como VAZIO. Remova a anotação para rodar isolado."
  echo
fi

# Lê o XML da última rodada e devolve o veredito honesto.
veredito() {
  python3 - "$RESULTADOS" <<'PY'
import glob, os, re, sys
pasta = sys.argv[1]
xmls = glob.glob(os.path.join(pasta, "TEST-*.xml"))
if not xmls:
    print("SEM RESULTADO"); raise SystemExit
t = s = f = e = 0
for x in xmls:
    cab = re.search(r"<testsuite\b[^>]*>", open(x, encoding="utf-8", errors="replace").read())
    if not cab:
        continue
    g = lambda k: int((re.search(r'\b%s="(\d+)"' % k, cab.group(0)) or [0, 0])[1])
    t += g("tests"); s += g("skipped"); f += g("failures"); e += g("errors")
if t == 0:
    print('VAZIO (tests="0")')
elif f or e:
    print("FALHOU")
elif s:
    print("PULADO (%d de %d)" % (s, t))
else:
    print("ok (%d)" % t)
PY
}

METODOS=$(grep -A1 '^\s*@Test' "$ARQ" | grep -oE 'fun [a-zA-Z0-9_]+' | sed 's/fun //')
TOTAL=0; VERDES=0; FALHAS=()

for m in $METODOS; do
  TOTAL=$((TOTAL+1))
  printf '  %-58s ' "$m"
  rm -f "$RESULTADOS"/TEST-*.xml
  if ./gradlew :app:connectedDebugAndroidTest \
       -Pandroid.testInstrumentationRunnerArguments.class="$CLASSE#$m" \
       >/dev/null 2>&1; then
    V=$(veredito)
  else
    V="FALHOU"
  fi
  echo "$V"
  case "$V" in
    ok*) VERDES=$((VERDES+1)) ;;
    *)   FALHAS+=("$m ($V)") ;;
  esac
done

echo
echo "$VERDES/$TOTAL executaram e passaram"
[ ${#FALHAS[@]} -gt 0 ] && { printf 'não passaram: %s\n' "${FALHAS[*]}"; exit 1; }
[ "$TOTAL" -eq 0 ] && { echo "nenhum @Test na classe — nada foi executado"; exit 1; }
exit 0
