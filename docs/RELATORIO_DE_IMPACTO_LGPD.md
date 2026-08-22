# Relatório de Impacto à Proteção de Dados Pessoais — Claryon Field

**Elaborado voluntariamente nos termos do art. 38 da Lei 13.709/2018 (LGPD).**
Versão 1 · 2026-08-22 · aplicável ao Claryon Field, copiloto de voz embarcado para
Ray-Ban Meta sem display, no contexto de segurança pública.

> Por que voluntário: o art. 38 faculta à ANPD **requisitar** o relatório. Nenhum
> órgão o requisitou. Ele é apresentado porque tratamento de dados por agente de
> segurança pública em via pública, com microfone e câmera vestidos, é
> **operação de alto risco por natureza** — e porque cada decisão descrita abaixo já
> está implementada e é verificável no código, não prometida.

---

## 1. Agentes e papéis

| Papel | Quem | Base |
|---|---|---|
| **Controlador** | A corporação de segurança pública contratante | art. 5º, VI |
| **Operador** | O fornecedor do Claryon Field | art. 5º, VII |
| **Titulares** | (a) o agente que veste os óculos; (b) terceiros no ambiente | art. 5º, V |

O tratamento de dados por órgão de segurança pública é regido pelo **art. 4º, III, "d"**
da LGPD, que remete a legislação específica. Isto **não** dispensa proporcionalidade,
necessidade e segurança — e é sobre esses três eixos que este relatório responde.

---

## 2. Dados tratados, e os que deliberadamente NÃO são

### 2.1 Tratados

| Dado | Finalidade | Onde vive | Retenção |
|---|---|---|---|
| Voz do agente (PCM) | Transcrição na origem | RAM do celular | Descartada após transcrever |
| Voz do agente (PCM) **sob gravação de evidência** | Prova de ocorrência | `EncryptedFile` no aparelho, cifrado | [`PoliticaDeRetencao`](../core-evidence/src/main/kotlin/com/claryon/evidence/EvidenceVault.kt); o padrão **não apaga** |
| Transcrição da fala do agente | Comunicação da guarnição | Servidor, canal privado por JWT | Duas camadas (§5) |
| Posição do agente | Coordenação da guarnição | Servidor | Duas camadas (§5) |
| Placa de veículo | Consulta a base veicular | RAM, durante a consulta | Não persistida |
| Frame de câmera | OCR de placa | **Somente RAM** | Descartado no mesmo ciclo |

A segunda linha é a única em que áudio é **persistido**, e ela só existe depois de um
comando explícito do agente (`Intent.IniciarGravacao`, disparado pela fala "iniciar
gravação"). O tráfego de rádio (PTT) **não** alimenta esse cofre — ver §8.6.

### 2.2 Não tratados — decisão de projeto, não limitação técnica

Estas são **proibições duras** do projeto, registradas em `CLAUDE.md §2` e sustentadas
por teste ou pelo compilador:

- ❌ **Reconhecimento facial ou base biométrica.** Nenhuma. Em nenhuma versão.
- ❌ **Fala de terceiros.** O beamforming dos óculos isola quem os veste. Transcrevemos
  o agente, **nunca o interlocutor** — e a rota de áudio dos óculos é
  **pré-condição de compilação** da captura: gravar pelo microfone do celular
  não compila.
- ❌ **Coordenada de terceiro.** O servidor devolve **grandezas** (distância, rumo),
  jamais a posição absoluta de outro agente.
- ❌ **Áudio, frame e transcrição LITERAL para serviço externo.** Absoluto para os três.
  **Revogado em parte em 22/08:** a consulta geoespacial por categoria
  (`specs/consulta-externa.spec.md`) sai pela rede **depois** que a fonte local não
  respondeu, e o que sai é uma constante de vocabulário fechado (`hospital`,
  `delegacia`, `posto de saude`) mais a **própria** coordenada arredondada a quatro
  casas (~11 m). Não sai transcrição, nem placa, nem matrícula, nem nome, nem
  indicativo, nem token de sessão — a classe de consulta **não recebe** nenhum deles.
  Verificado por teste que inspeciona o corpo HTTP num socket real
  (`ConsultaGeoespacialTest`) e por teste que parte da fala envenenada e mede o que
  atravessa a fronteira (`ConsultaExternaNoExecutorTest`).

---

## 3. Necessidade e proporcionalidade (art. 6º, III)

O agente de segurança pública trabalha com as mãos ocupadas e a atenção no ambiente.
Hoje ele **tira o rádio do coldre** ou **para para digitar** — e cada segundo com os
olhos no aparelho é um segundo sem olhar para a cena.

A finalidade legítima é reduzir esse desvio de atenção. O que é **necessário** para
isso é: ouvir o agente, falar com o agente, saber onde a guarnição está, e consultar
bases oficiais. **Não é necessário** saber quem está na frente dele, gravar quem passa,
ou registrar rostos. Por isso essas capacidades não existem — e a diferença entre
"não usamos" e "não existe" é o que este relatório sustenta.

---

## 4. Risco, medida adotada e risco residual assumido

| # | Risco identificado | Medida adotada (verificável) | Risco residual |
|---|---|---|---|
| **R1** | Captura da voz de terceiro (transeunte, vítima, suspeito) | Beamforming isola o portador; a rota HFP dos óculos é pré-condição **de compilação** da captura; o pré-roll do PTT vive em RAM e nunca é persistido | Ambiente muito silencioso e interlocutor muito próximo podem vazar fonemas isolados no fluxo do portador. **Assumido**: não há transcrição dirigida ao terceiro e nada é indexado por pessoa |
| **R2** | Trilateração da posição de um agente por um par | Função de servidor **nunca** recebe a identidade do solicitante como parâmetro — ela vem do JWT. A função `locate` que aceitava `solicitante_id` foi **apagada**, e a migração `0006` fechou a brecha no banco | Um solicitante com múltiplas contas legítimas poderia cruzar grandezas. **Mitigado** por log de acesso (`0017`) e distância arredondada (`0021`) |
| **R3** | Retenção indefinida de posição | Retenção em duas camadas (`0019`): dado operacional expira; o histórico agregado não identifica trajeto | Camada agregada ainda permite inferência estatística sobre área de patrulha. **Assumido** por ser necessário ao planejamento operacional |
| **R4** | Vazamento de evidência no aparelho | `EncryptedFile` + Android Keystore, com cadeia de hash — adulterar um byte aponta o segmento | Ver R8: a chave da âncora é usável **pelo próprio app** |
| **R8** | **Truncamento da evidência** — apagar o fim de uma gravação sem deixar rastro | Até 2026-08-22 não havia medida: hash encadeado detecta alteração e é **cego a remoção no fim**, então apagar os últimos segmentos e as linhas correspondentes produzia uma cadeia perfeita e um veredito de integridade. Agora o manifesto v3 carrega **âncora de fim** (HMAC-SHA256, chave do Keystore) sobre (nº de segmentos, último hash, taxa, formato, motivo do fim, purgas), e a conferência **fecha por falta**: sem âncora válida, `Integra` é inalcançável. Provado por mutação — reintroduzir o defeito derruba exatamente 5 testes | A chave vive no Keystore do aparelho e é usável **pelo próprio app**: quem executar código como o app (root com injeção, ou compilação adulterada) sela âncora válida para qualquer cadeia. A barra subiu de "qualquer acesso de escrita ao diretório" para "executar como o app", e **não além**. Fechar exige âncora **externa** (servidor ou HSM da corregedoria) — **não construída**. Respaldo em hardware da chave **não medido** |
| **R5** | Envio de dado sensível a terceiro | IA 100% local: os três modelos estão **dentro do APK**, e nenhum modelo roda fora do aparelho. A **única** saída a terceiro é a consulta geoespacial (OSM/Overpass, desde 22/08), e o que sai é categoria de vocabulário fechado + coordenada **própria** arredondada a ~11 m — sem transcrição, sem placa, sem matrícula, sem nome, sem indicativo, sem token. Prazo de 2 s; estourar é recusa. Procedência (serviço, consulta emitida, trecho, carimbo) registrada em **toda** resposta, inclusive na que não acha nada | O terceiro é um serviço público estrangeiro e vê o **endereço IP** do aparelho junto de uma coordenada de ~11 m — é uma inferência de posição que não existia antes, e ela é **assumida**. A estatística de uso que sai do aparelho tem granularidade de **dia** e nenhum identificador, por construção (`RegistroDeUso`). Base veicular oficial é consulta a sistema do próprio Estado, com `procedencia` declarada em toda resposta |
| **R6** | Acionamento acidental do copiloto | Palavra de ativação com limiar configurável e earcon audível — o agente **sempre ouve** quando o sistema acorda | Falso positivo medido em **2,08/h** contra meta de 0,5/h. **Não resolvido**, declarado, e o earcon garante que nunca é silencioso |
| **R7** | O sistema afirmar o que não sabe | Vocabulário de fala fechado: `utteranceFor` aceita **apenas** `ActionOutcome`, então a fala deriva do resultado da ação e nunca do comando. Recusa é falada, não silenciosa | Modelo de linguagem local pode inventar número: medido **25 em 268**. Por isso a Etapa B **não está no caminho de produção** |

---

## 5. Retenção em duas camadas

A separação existe porque "apagar tudo" e "guardar tudo" são ambos errados:

- **Camada operacional** — posição e tráfego do turno. Existe para coordenar a
  guarnição agora. Expira.
- **Camada agregada** — não permite reconstruir trajeto individual. Existe para
  planejamento.

Implementada em `servidor/migracoes/0019_retencao_em_duas_camadas.sql`.
**Ressalva honesta:** a execução automática por `cron` ainda não foi comprovada em
`cron.job_run_details`; a única execução verificada foi manual.

---

## 6. Segurança e controle de acesso (art. 46)

O controle de acesso **não** é feito por política de linha na aplicação. É feito por
arquitetura de banco:

- Funções sensíveis vivem no schema `private`, **fora do PostgREST**.
- Sem `GRANT` para os papéis anônimo e autenticado.
- Acesso apenas por função `SECURITY DEFINER` que deriva a identidade do **JWT**.
- Log de acesso nas duas portas (`0017_log_de_acesso.sql`).

Consequência: mesmo com o token de um agente válido, não há superfície para pedir o
dado de outro.

---

## 7. Direitos do titular (art. 18)

| Direito | Como é atendido |
|---|---|
| Confirmação e acesso | Consulta "quem me consultou" disponível ao próprio agente |
| Correção | Posição é sempre a última publicada pelo próprio aparelho |
| Anonimização/eliminação | Camada operacional expira; a agregada não identifica |
| Informação sobre compartilhamento | Toda resposta da base veicular declara `procedencia` (oficial/demonstração) |
| Revogação | Encerrar turno interrompe a coleta; revogação de JWT corta o canal |

---

## 8. O que este relatório NÃO afirma

Coerente com a regra do projeto de que documento não descreve capacidade inexistente:

1. A âncora de fim **não** torna a custódia inforjável. Ela impede o truncamento por
   quem tem acesso ao **disco**; não impede quem executa **como o app**, porque é o
   Keystore do próprio aparelho que assina. Custódia inforjável exige âncora externa,
   que **não existe**. Onde este relatório disser "auditável", leia
   *auditável contra adulteração de terceiro no aparelho* — não *inforjável pelo
   operador*.
2. A execução automática da retenção **não foi comprovada** em produção.
3. O falso positivo da palavra de ativação está **4,16× acima** da meta.
4. `security-crypto` está em versão **alpha**.
5. Consumo de bateria e comportamento térmico com os óculos **não foram medidos**.
6. **O cofre não guarda o tráfego de rádio.** `EvidenceVault` tem exatamente dois
   chamadores em `src/main` (`ClaryonIntentExecutor` e `CopilotoDoAgente`), e os dois
   partem de `Intent.IniciarGravacao` — o comando de voz "iniciar gravação". Nada no
   caminho do PTT escreve no cofre. Quem quiser afirmar que "as comunicações de rádio
   ficam guardadas e auditáveis" está descrevendo capacidade que **não existe**: o que
   sobrevive de uma transmissão é a **transcrição** no servidor, não o áudio.
7. **A conferência tem caminho no produto desde 22/08 — a EXTRAÇÃO não.**
   `EncryptedEvidenceVault.verificar` e `Manifesto.ler` passaram semanas com **zero
   chamadores em `src/main`**: o produto selava a âncora de fim em produção e conferia
   só em teste, e periciar exigia `adb`/root sobre o diretório privado — exatamente o
   acesso que o modelo de ameaça trata como atacante. Pela régua do próprio projeto
   (`CLAUDE.md §6`), a conferência estava *escrita, não construída*.
   `EncryptedEvidenceVault.periciar` fecha isso: **Perfil → Periciar a custódia** lista
   as gravações seladas com o veredito de cada uma, e o caminho é
   `PericiaViewModel` → `periciar()` → `verificar(handle)`, com teste que reprova o
   build se o chamador sumir.
   **O que continua não existindo:** exportação. Levar os segmentos e o manifesto para
   fora do aparelho ainda exige `adb`, e a tela diz isso em vez de deixar a ausência
   parecer capacidade. E o veredito `Confere` continua sujeito à ressalva do R8 — a
   tela repete a limitação palavra por palavra, e um teste falha se ela for apagada de
   qualquer um dos dois lugares.

---

## 9. Conclusão

O desenho parte de uma inversão deliberada: em vez de coletar e depois restringir o
uso, o Claryon **não constrói a capacidade de coletar** o que não precisa. Ausência de
reconhecimento facial não é configuração — é ausência de código. A não-transcrição de
terceiros não é política — é pré-condição de compilação. A impossibilidade de
trilaterar não é regra de negócio — é assinatura de função.

É por isso que este relatório pode ser verificado por `grep`, e não apenas lido.

---

*Documento vivo. Alterado sempre que uma das medidas acima mudar de estado.*
