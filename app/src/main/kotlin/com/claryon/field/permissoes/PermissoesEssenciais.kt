package com.claryon.field.permissoes

import android.Manifest
import android.os.Build

/** O que o produto sabe fazer. Permissão negada apaga capacidades, não features. */
enum class Capacidade(val rotulo: String) {
    OUVIR_COMANDOS("entender comandos de voz"),
    RADIO_PTT("falar no rádio tático"),
    GRAVAR_EVIDENCIA("gravar evidência"),
    CONECTAR_OCULOS("conectar aos óculos"),
    VER_PLACA("ler placa pela câmera"),
    POSICAO_NO_MAPA("posição no mapa"),
    ALERTA_COM_LOCAL("alerta com localização"),
    AVISO_EM_SEGUNDO_PLANO("aviso em segundo plano"),
}

/**
 * Uma permissão e **o que exatamente para de funcionar sem ela**.
 *
 * O campo [semEla] não é texto de marketing: é a frase que o agente ouve e lê
 * quando a capacidade morre. Escrito no vocabulário dele — "o rádio não fala",
 * não "RECORD_AUDIO negada".
 */
data class PermissaoEssencial(
    val permissao: String,
    val porQue: String,
    val semEla: String,
    /** Sem ela **não existe produto**. Só o microfone é assim. */
    val bloqueante: Boolean,
    val capacidades: List<Capacidade>,
)

/** O que o app pode fazer agora, e o que fazer a respeito. */
data class EstadoDePermissoes(
    val faltando: List<PermissaoEssencial>,
    val capacidadesPerdidas: List<Capacidade>,
) {
    /** `false` quando falta uma permissão bloqueante — o app não deve fingir que opera. */
    val podeOperar: Boolean get() = faltando.none { it.bloqueante }

    val tudoConcedido: Boolean get() = faltando.isEmpty()
}

/** O caminho de volta depende de **como** a permissão foi negada. */
sealed interface Recuperacao {
    /** Ainda dá para pedir pelo diálogo do sistema. */
    data class Pedir(val permissoes: List<String>) : Recuperacao

    /**
     * Negada em definitivo: o diálogo do sistema não abre mais. O único caminho
     * são os ajustes do aplicativo — e o app tem de dizer isso, porque para o
     * agente o sintoma é "apertei e não aconteceu nada".
     */
    data class AbrirAjustes(val permissoes: List<String>) : Recuperacao

    data object Nada : Recuperacao
}

/**
 * **Catálogo de permissões, com o preço de cada negativa.**
 *
 * Existe separado da tela por dois motivos. O primeiro é testabilidade: as
 * regras são função pura de `(quais estão concedidas) → (o que morre)`, e dá
 * para varrer todas as combinações na JVM.
 *
 * O segundo é o que realmente importa. Num app cuja saída é áudio, **permissão
 * negada é a falha mais fácil de esconder**: o agente fala, nada acontece, e ele
 * conclui que o produto é ruim — não que faltou um toque numa caixa de diálogo.
 * Por isso toda negativa tem uma frase própria, dita em voz alta, e não um
 * silêncio educado.
 *
 * O que **não** está aqui, também de propósito:
 *
 *  - `ACCESS_BACKGROUND_LOCATION`. O serviço em primeiro plano com tipo
 *    `location` já cobre o turno inteiro. Pedir a de segundo plano acrescentaria
 *    um diálogo assustador ("o tempo todo") sem acrescentar capacidade.
 *  - Qualquer permissão de armazenamento. A evidência vive em `EncryptedFile` no
 *    diretório privado do app; nada dela sai para a mídia compartilhada.
 */
object PermissoesEssenciais {

    val MICROFONE = PermissaoEssencial(
        permissao = Manifest.permission.RECORD_AUDIO,
        porQue = "É por ela que o Claryon ouve seus comandos e transmite no rádio.",
        semEla = "Sem microfone, o Claryon não funciona.",
        bloqueante = true,
        capacidades = listOf(
            Capacidade.OUVIR_COMANDOS,
            Capacidade.RADIO_PTT,
            Capacidade.GRAVAR_EVIDENCIA,
        ),
    )

    val BLUETOOTH = PermissaoEssencial(
        permissao = Manifest.permission.BLUETOOTH_CONNECT,
        porQue = "É por ela que o app encontra os óculos e usa o microfone deles.",
        semEla = "Sem Bluetooth, os óculos não conectam.",
        // Não bloqueante por decisão de produto: com fone comum o ciclo de voz
        // roda inteiro. Perde-se o beamforming — o que muda quem é gravado, e é
        // avisado — mas o app continua sendo útil.
        bloqueante = false,
        capacidades = listOf(Capacidade.CONECTAR_OCULOS),
    )

    val LOCALIZACAO = PermissaoEssencial(
        permissao = Manifest.permission.ACCESS_FINE_LOCATION,
        porQue = "É por ela que a guarnição sabe onde você está — e você, onde eles estão.",
        semEla = "Sem local, ninguém aparece no mapa.",
        bloqueante = false,
        capacidades = listOf(Capacidade.POSICAO_NO_MAPA, Capacidade.ALERTA_COM_LOCAL),
    )

    val CAMERA = PermissaoEssencial(
        permissao = Manifest.permission.CAMERA,
        porQue = "É por ela que o app lê placas pela câmera dos óculos.",
        semEla = "Sem câmera, não leio placas.",
        bloqueante = false,
        capacidades = listOf(Capacidade.VER_PLACA),
    )

    val NOTIFICACAO = PermissaoEssencial(
        permissao = Manifest.permission.POST_NOTIFICATIONS,
        porQue = "É por ela que o app continua ouvindo com a tela apagada.",
        semEla = "Sem notificação, o app para com a tela apagada.",
        bloqueante = false,
        capacidades = listOf(Capacidade.AVISO_EM_SEGUNDO_PLANO),
    )

    /**
     * Tudo que o produto usa, **independente da versão do Android**.
     *
     * Separado de [catalogo] porque as duas perguntas são diferentes: "o que este
     * app precisa" é fixo; "o que pedir neste aparelho" depende da versão. Juntar
     * as duas fazia a verificação de cobertura acusar `AVISO_EM_SEGUNDO_PLANO`
     * como capacidade órfã no Android 12 — onde ela existe e funciona, só que
     * concedida na instalação.
     */
    val TODAS = listOf(MICROFONE, BLUETOOTH, LOCALIZACAO, CAMERA, NOTIFICACAO)

    /**
     * Na ordem em que devem ser pedidas: **a bloqueante primeiro**.
     *
     * Ordem importa. Pedir câmera antes do microfone gasta a paciência do agente
     * na permissão menos importante, e o diálogo que ele mais provavelmente nega
     * é o último que aparece.
     */
    fun catalogo(): List<PermissaoEssencial> = buildList {
        add(MICROFONE)
        add(BLUETOOTH)
        add(LOCALIZACAO)
        add(CAMERA)
        // POST_NOTIFICATIONS só existe como permissão de runtime no Android 13+.
        // Antes disso, pedir devolveria "negada" para sempre — e o app anunciaria
        // uma capacidade perdida que na verdade está inteira.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(NOTIFICACAO)
    }

    fun avaliar(concedidas: Set<String>): EstadoDePermissoes {
        val faltando = catalogo().filterNot { it.permissao in concedidas }
        return EstadoDePermissoes(
            faltando = faltando,
            capacidadesPerdidas = faltando.flatMap { it.capacidades }.distinct(),
        )
    }

    /**
     * O caminho de volta.
     *
     * @param podePedirDeNovo para cada permissão, se o diálogo do sistema ainda
     *   abre (`shouldShowRequestPermissionRationale` ou primeira tentativa).
     */
    fun recuperacao(
        estado: EstadoDePermissoes,
        podePedirDeNovo: (String) -> Boolean,
    ): Recuperacao {
        if (estado.tudoConcedido) return Recuperacao.Nada
        val pedíveis = estado.faltando.map { it.permissao }.filter(podePedirDeNovo)
        return if (pedíveis.isNotEmpty()) {
            Recuperacao.Pedir(pedíveis)
        } else {
            Recuperacao.AbrirAjustes(estado.faltando.map { it.permissao })
        }
    }

    /**
     * O que o agente **ouve** quando falta permissão — `null` quando não falta
     * nada.
     *
     * Uma frase só, mesmo faltando três permissões. Não é economia de texto: uma
     * lista falada de negativas num alto-falante *open-ear*, no meio da rua, é
     * ruído que o agente aprende a ignorar. A tela mostra o detalhe; o ouvido
     * recebe a manchete.
     */
    fun avisoFalado(estado: EstadoDePermissoes): String? = when {
        estado.tudoConcedido -> null
        !estado.podeOperar -> MICROFONE.semEla
        estado.faltando.size == 1 -> estado.faltando.first().semEla
        else -> "Permissões faltando. Veja a tela."
    }
}
