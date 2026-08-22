package com.claryon.field.ui.telas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.claryon.evidence.Integridade
import com.claryon.evidence.RegistroDeCustodia
import com.claryon.field.ui.PericiaViewModel
import com.claryon.field.ui.componentes.BotaoTatico
import com.claryon.field.ui.componentes.CabecalhoTatico
import com.claryon.field.ui.componentes.Etiqueta
import com.claryon.field.ui.componentes.Fio
import com.claryon.field.ui.componentes.PilulaDeAcao
import com.claryon.field.ui.componentes.PontoDeEstado
import com.claryon.field.ui.componentes.TextoCorpoMenor
import com.claryon.field.ui.componentes.TextoDado
import com.claryon.field.ui.componentes.Vazio
import com.claryon.field.ui.icones.Icones
import com.claryon.field.ui.tema.Cores
import com.claryon.field.ui.tema.Espaco
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * **A perícia da custódia — a tela que faltava para a conferência existir.**
 *
 * `EncryptedEvidenceVault.verificar` e `Manifesto.ler` tinham **zero chamadores em
 * `src/main`** até 22/08: o produto **selava** a âncora de fim em produção e
 * **conferia só em teste**. Periciar exigia `adb`/root sobre o diretório privado do
 * app — exatamente o acesso que o modelo de ameaça trata como atacante. Esta tela é
 * o caminho que faltava.
 *
 * ## Sem cor, de propósito
 *
 * Uma custódia quebrada é grave, e a tentação de pintá-la de vermelho é forte. A
 * decisão 2 da paleta manda o contrário — cor é sinal reservado a "no ar" e a
 * prioridade —, e o precedente já existe uma tela ao lado: [TelaDePerfil] marca a
 * capacidade **morta** com tinta mais forte, não com vermelho. Aqui a gravidade sai
 * em três níveis de tinta acromática, pela mesma régua.
 *
 * ## O que a tela promete, e o que ela não promete
 *
 * Ela responde *"o que aconteceu com esta prova?"*. Ela **não** extrai nada: os
 * segmentos e o manifesto continuam saindo do aparelho por `adb`, e a tela diz isso
 * em vez de deixar a ausência parecer capacidade. E ela nunca chama um veredito de
 * "inviolável" — ver [RessalvaDaAncora], que é a mesma ressalva de
 * `docs/RELATORIO_DE_IMPACTO_LGPD.md` R8, palavra por palavra em espírito.
 */
@Composable
fun TelaDePericia(
    estado: PericiaViewModel.Estado,
    aoConferir: () -> Unit,
    aoVoltar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        CabecalhoTatico(etiqueta = "Cadeia de custódia", titulo = "Perícia")

        Column(Modifier.padding(Espaco.Padrao)) {
            TextoCorpoMenor(
                "Confere cada segmento gravado contra o manifesto e a âncora de fim. " +
                    "A conferência decifra o áudio guardado — ela começa por um toque, " +
                    "e nunca sozinha.",
                cor = Cores.TintaFraca,
            )
            Box(Modifier.height(Espaco.Medio))
            PilulaDeAcao(
                rotulo = if (estado is PericiaViewModel.Estado.Conferido) {
                    "Conferir de novo"
                } else {
                    "Conferir agora"
                },
                icone = Icones.Recarregar,
                aoTocar = aoConferir,
                habilitado = estado !is PericiaViewModel.Estado.Conferindo,
            )
        }

        Fio()

        when (estado) {
            is PericiaViewModel.Estado.Ocioso -> Vazio(
                etiqueta = "Nada conferido ainda",
                explicacao = "Toque em conferir para ler o cofre deste aparelho.",
            )

            is PericiaViewModel.Estado.Conferindo -> Vazio(
                etiqueta = "Conferindo",
                explicacao = "Decifrando e re-hasheando os segmentos guardados.",
            )

            is PericiaViewModel.Estado.Indisponivel -> Vazio(
                etiqueta = "Não foi possível conferir",
                // "Não consegui ler" e "nada gravado" levam a conclusões opostas
                // numa tela cuja razão de existir é responder o que houve com a prova.
                explicacao = estado.motivo + " Isto NÃO significa que não há gravações.",
            )

            is PericiaViewModel.Estado.Conferido -> if (estado.registros.isEmpty()) {
                Vazio(
                    etiqueta = "Nenhuma gravação selada",
                    // É o estado normal e ele precisa ser dito: só o comando de voz
                    // "iniciar gravação" alimenta o cofre, e nada no caminho do PTT
                    // escreve nele. Ver `docs/RELATORIO_DE_IMPACTO_LGPD.md` §8.6.
                    explicacao = "O cofre só guarda o que foi gravado por comando. " +
                        "O tráfego de rádio não entra nele.",
                )
            } else {
                Column {
                    Box(Modifier.height(Espaco.Medio))
                    Column(Modifier.padding(horizontal = Espaco.Padrao)) {
                        Etiqueta("${estado.registros.size} gravação(ões)")
                        Box(Modifier.height(Espaco.Micro))
                        TextoCorpoMenor(
                            "Conferido às ${horaLocal(estado.em)}. O veredito vale " +
                                "para esse instante.",
                            cor = Cores.TintaFraca,
                        )
                        Box(Modifier.height(Espaco.Medio))
                    }
                    for (r in estado.registros) {
                        Fio()
                        LinhaDeCustodia(r)
                    }
                    Fio()
                }
            }
        }

        Box(Modifier.height(Espaco.Secao))
        RessalvaDaAncora()
        Box(Modifier.height(Espaco.Secao))

        Column(Modifier.padding(horizontal = Espaco.Padrao)) {
            BotaoTatico("Voltar", aoVoltar)
        }
        Box(Modifier.height(Espaco.Secao))
    }
}

/**
 * **A ressalva, e ela não é rodapé.**
 *
 * `CONFERE` não quer dizer *inviolável*, e uma tela de perícia que deixe essa
 * leitura de pé mente por omissão para quem mais depende dela. A chave da âncora
 * vive no Keystore **deste** aparelho e é usável **pelo próprio aplicativo**: quem
 * executar código como o app sela âncora válida para a cadeia que quiser.
 *
 * O texto é o mesmo compromisso do R8 de `docs/RELATORIO_DE_IMPACTO_LGPD.md` e do
 * KDoc de `AncoraDeFim`. Os três dizem a mesma coisa de propósito — a tela não pode
 * ser o único lugar do produto onde a limitação some.
 */
@Composable
private fun RessalvaDaAncora() {
    Column(Modifier.padding(horizontal = Espaco.Padrao)) {
        Etiqueta("O que \"confere\" significa")
        Box(Modifier.height(Espaco.Curto))
        TextoCorpoMenor(
            "Confere não é inforjável. A chave da âncora vive no Keystore deste " +
                "aparelho e é usável pelo próprio aplicativo: quem executar código " +
                "como o app — aparelho com root e injeção, ou uma compilação " +
                "adulterada — sela uma âncora válida para qualquer cadeia.",
            cor = Cores.TintaMedia,
        )
        Box(Modifier.height(Espaco.Curto))
        TextoCorpoMenor(
            "O que a âncora fecha é o truncamento por quem tem acesso ao disco: " +
                "apagar o fim da gravação deixa de passar despercebido. A barra subiu " +
                "de \"qualquer acesso de escrita ao diretório\" para \"executar como " +
                "o app\", e não além. Custódia inforjável exigiria âncora externa — " +
                "servidor ou HSM da corregedoria —, que não existe neste produto.",
            cor = Cores.TintaFraca,
        )
        Box(Modifier.height(Espaco.Curto))
        TextoCorpoMenor(
            "Esta tela confere; ela não exporta. Levar os segmentos e o manifesto " +
                "para fora do aparelho continua exigindo acesso por adb.",
            cor = Cores.TintaFraca,
        )
    }
}

@Composable
private fun LinhaDeCustodia(r: RegistroDeCustodia) {
    val gravidade = Veredito.gravidade(r.veredito, r.emAndamento)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Espaco.Padrao, vertical = Espaco.Medio),
        verticalAlignment = Alignment.Top,
    ) {
        PontoDeEstado(tintaDe(gravidade))
        Box(Modifier.width(Espaco.Medio))
        Column(Modifier.weight(1f)) {
            TextoDado(Veredito.rotulo(r.veredito, r.emAndamento), cor = tintaDe(gravidade))
            Box(Modifier.height(Espaco.Micro))
            TextoCorpoMenor(Veredito.explicacao(r.veredito, r.emAndamento), cor = Cores.TintaMedia)
            Box(Modifier.height(Espaco.Curto))
            TextoCorpoMenor(descricaoDoRegistro(r), cor = Cores.TintaFraca)
            Box(Modifier.height(Espaco.Micro))
            // O identificador é o que a corregedoria cita num ofício. Ele é o nome
            // do diretório no aparelho, e por isso vale mais que qualquer rótulo
            // bonito que a tela inventasse.
            TextoCorpoMenor(r.handle.id, cor = Cores.TintaFraca)
        }
    }
}

/**
 * Três níveis de tinta, nenhum de cor.
 *
 * A mais forte é a que exige leitura — mesma inversão de [TelaDePerfil], onde a
 * capacidade **morta** é a que sai em `Tinta` e a viva em `TintaFraca`. Num painel
 * escuro, brilho é o que a visão periférica pega.
 */
private fun tintaDe(g: Veredito.Gravidade) = when (g) {
    Veredito.Gravidade.GRAVE -> Cores.Tinta
    Veredito.Gravidade.RESSALVA -> Cores.TintaMedia
    Veredito.Gravidade.CONFERE -> Cores.TintaFraca
}

/**
 * **A tradução do veredito para o vocabulário de quem periciar.**
 *
 * Objeto puro, sem Compose e sem Android, porque é a parte com regra: cada
 * [Integridade] tem de sair da tela com um nome distinto, uma explicação que diga o
 * que *aconteceu com a prova*, e uma gravidade. `EncryptedEvidenceVaultTest`
 * responde se o veredito está certo; `TelaDePericiaTest` responde se ele chega
 * inteiro ao agente.
 *
 * O `when` é exaustivo por construção: [Integridade] é `sealed`, então acrescentar
 * um veredito novo sem lhe dar texto **não compila**. É a única parte desta tela que
 * o compilador sustenta sozinho, e ela é a parte que importa.
 */
object Veredito {

    /**
     * Quanto isto exige de quem lê.
     *
     * Três e não dois: colapsar [RESSALVA] em [GRAVE] faria a política de retenção
     * do próprio produto — que apaga segmento por decisão jurídica — aparecer com o
     * mesmo peso de uma cadeia adulterada. Colapsá-la em [CONFERE] esconderia que
     * há segmento cuja integridade é, por construção, indemonstrável.
     */
    enum class Gravidade { CONFERE, RESSALVA, GRAVE }

    fun rotulo(v: Integridade, emAndamento: Boolean = false): String = when {
        emAndamento -> "Gravando agora"
        else -> when (v) {
            is Integridade.Integra -> "Confere"
            is Integridade.ExpurgadaPorPolitica -> "Confere, com expurgo"
            is Integridade.Quebrada -> "Cadeia quebrada"
            is Integridade.SegmentoNaoRegistrado -> "Segmento sem registro"
            is Integridade.Truncada -> "Truncada"
            is Integridade.SemAncoraDeFim -> when (v.motivo) {
                Integridade.SemAncoraDeFim.Motivo.NAO_FINALIZADA -> "Sem fim selado"
                Integridade.SemAncoraDeFim.Motivo.FORMATO_ANTERIOR -> "Formato sem âncora"
                Integridade.SemAncoraDeFim.Motivo.AUSENTE -> "Âncora ausente"
                Integridade.SemAncoraDeFim.Motivo.INVALIDA -> "Âncora não confere"
                Integridade.SemAncoraDeFim.Motivo.CHAVE_INDISPONIVEL -> "Chave indisponível"
            }
        }
    }

    fun explicacao(v: Integridade, emAndamento: Boolean = false): String = when {
        emAndamento ->
            "A gravação está aberta neste aparelho. Ainda não há fim para ancorar, " +
                "e por isso ela não foi conferida."

        else -> when (v) {
            is Integridade.Integra ->
                "Todos os segmentos presentes conferem entre si, e a âncora de fim " +
                    "é autêntica para esta cadeia."

            is Integridade.ExpurgadaPorPolitica ->
                "${v.sequencias.size} segmento(s) foram apagados pela política de " +
                    "retenção, com o motivo registrado no manifesto. O restante " +
                    "confere; a integridade do que foi apagado é indemonstrável por " +
                    "construção."

            is Integridade.Quebrada ->
                "O segmento ${v.sequencia} não decifra, ou o hash dele diverge do " +
                    "manifesto. A partir dele, o conteúdo não é o que foi selado."

            is Integridade.SegmentoNaoRegistrado ->
                "Há o segmento ${v.sequencia} no disco além do último registrado no " +
                    "manifesto — o que um processo morto entre gravar o arquivo e " +
                    "anexar a linha produz. Não é adulteração."

            is Integridade.Truncada ->
                "Foram selados ${v.seladosNoFim} segmentos e o manifesto apresenta " +
                    "${v.presentesNoManifesto}. A âncora é assinada e não acompanha " +
                    "o corte: o fim desta gravação foi removido."

            is Integridade.SemAncoraDeFim -> when (v.motivo) {
                Integridade.SemAncoraDeFim.Motivo.NAO_FINALIZADA ->
                    "Não há linha de fim: o processo morreu antes de fechar a " +
                        "gravação. Não houve o que ancorar, e nada prova que este é " +
                        "o fim dela."

                Integridade.SemAncoraDeFim.Motivo.FORMATO_ANTERIOR ->
                    "Manifesto anterior à versão que carrega âncora de fim. " +
                        "Rebaixar a versão para escapar da exigência cai aqui " +
                        "também — nos dois casos, nada prova onde a gravação " +
                        "terminou."

                Integridade.SemAncoraDeFim.Motivo.AUSENTE ->
                    "Manifesto finalizado e sem a linha de âncora: ou a chave falhou " +
                        "ao selar, ou alguém a removeu. Para quem confere, os dois " +
                        "casos são o mesmo."

                Integridade.SemAncoraDeFim.Motivo.INVALIDA ->
                    "A âncora está presente e o HMAC não bate. Este manifesto não é " +
                        "o que foi selado."

                Integridade.SemAncoraDeFim.Motivo.CHAVE_INDISPONIVEL ->
                    "O Keystore não respondeu agora. Isto não diz nada sobre a " +
                        "gravação: diz que esta conferência não pôde ser feita neste " +
                        "aparelho."
            }
        }
    }

    fun gravidade(v: Integridade, emAndamento: Boolean = false): Gravidade = when {
        emAndamento -> Gravidade.RESSALVA
        else -> when (v) {
            is Integridade.Integra -> Gravidade.CONFERE
            // Expurgo é decisão registrada, não fraude — mas há segmento cuja
            // integridade não dá para demonstrar, e isso não é "confere".
            is Integridade.ExpurgadaPorPolitica -> Gravidade.RESSALVA
            is Integridade.SegmentoNaoRegistrado -> Gravidade.RESSALVA
            is Integridade.Quebrada -> Gravidade.GRAVE
            is Integridade.Truncada -> Gravidade.GRAVE
            is Integridade.SemAncoraDeFim -> when (v.motivo) {
                // Processo morto e Keystore mudo não acusam ninguém: o primeiro é
                // uma gravação sem fim provado, o segundo é uma conferência que não
                // pôde ser feita. Nenhum dos dois é sinal de adulteração.
                Integridade.SemAncoraDeFim.Motivo.NAO_FINALIZADA -> Gravidade.RESSALVA
                Integridade.SemAncoraDeFim.Motivo.CHAVE_INDISPONIVEL -> Gravidade.RESSALVA
                Integridade.SemAncoraDeFim.Motivo.FORMATO_ANTERIOR -> Gravidade.GRAVE
                Integridade.SemAncoraDeFim.Motivo.AUSENTE -> Gravidade.GRAVE
                Integridade.SemAncoraDeFim.Motivo.INVALIDA -> Gravidade.GRAVE
            }
        }
    }
}

/**
 * A linha de dados da gravação. Puro, para caber em teste de JVM.
 *
 * Manifesto da v1 não gravava o início, e `0` sai como "início não registrado" em
 * vez de virar 01/01/1970 — data plausível e falsa numa tela de perícia é pior que
 * a lacuna admitida.
 */
fun descricaoDoRegistro(
    r: RegistroDeCustodia,
    zona: ZoneId = ZoneId.systemDefault(),
): String {
    val inicio = if (r.inicioEpochMillis > 0) horaLocal(r.inicioEpochMillis, zona) else "não registrado"
    val fim = r.fimEpochMillis?.let { horaLocal(it, zona) } ?: "sem fim registrado"
    val purga = if (r.purgados > 0) ", ${r.purgados} purgado(s)" else ""
    val motivo = r.motivoDoFim?.let { " · parou por $it" } ?: ""
    return "Início $inicio · fim $fim · ${r.segmentos} segmento(s)$purga · " +
        "${tamanho(r.bytesRetidos)} de áudio retido · manifesto v${r.versao}$motivo"
}

/** Instante no fuso do aparelho. UTC cru já enganou uma tela de auditoria deste app. */
fun horaLocal(epochMillis: Long, zona: ZoneId = ZoneId.systemDefault()): String = runCatching {
    Instant.ofEpochMilli(epochMillis).atZone(zona).format(FORMATO)
}.getOrElse { epochMillis.toString() }

private val FORMATO: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")

/** kB e MB em base 1024 — a mesma unidade em que a reserva de disco do cofre é contada. */
fun tamanho(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MiB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.0f kiB".format(bytes / 1_024.0)
    else -> "$bytes B"
}
