package com.claryon.agent

/**
 * **O banco de elocuções de placa ditada — um só, para os dois testes que o usam.**
 *
 * Ele nasceu dentro de `BancoDePlacasDitadasTest`, medindo [PlacaDitada] sozinho.
 * Quando o roteador ganhou o caminho da ditada, `PlacaDitadaNoRoteadorTest` precisou
 * dos **mesmos** negativos — e copiá-los seria criar duas listas que divergem no
 * primeiro negativo acrescentado a uma delas. Um banco de referência que existe em
 * duas cópias não é referência.
 *
 * As elocuções e a proveniência de cada grupo estão documentadas em
 * `BancoDePlacasDitadasTest`, que continua sendo quem as **mede**. Aqui só moram os
 * dados.
 */
internal object BancoDePlacasDitadas {

    /** Uma elocução e o que ela DEVE produzir. `null` = tem de ser recusada. */
    data class Caso(val fala: String, val esperado: String?)

    private fun ok(fala: String, placa: String) = Caso(fala, placa)
    private fun nao(fala: String) = Caso(fala, null)

    /**
     * Placas ditadas em alfabeto fonético, do jeito limpo.
     *
     * O caso da spec (*"tango bravo unido três delta sete zero"* → `TBU3D70`) é o
     * primeiro, porque ele é o contrato escrito.
     */
    val foneticasLimpas = listOf(
        ok("Claryon, consultar placa tango bravo unido três delta sete zero.", "TBU3D70"),
        ok("Claryon, verificar placa alfa bravo charlie um dois três quatro.", "ABC1234"),
        ok("checar placa romeu índia oscar sete alfa dois um", "RIO7A21"),
        ok("rodar placa sierra papa oscar nove kilo zero zero", "SPO9K00"),
        ok("consultar placa lima uniforme zulu meia bravo zero um", "LUZ6B01"),
        ok("Claryon, consultar placa golf oscar lima quatro delta meia sete.", "GOL4D67"),
        ok("verificar placa mike novembro papa oito hotel quatro cinco", "MNP8H45"),
        ok("consultar placa echo delta uniforme dois foxtrot nove nove", "EDU2F99"),
        ok("rodar placa quebec romeu sierra zero tango um oito", "QRS0T18"),
        ok("checar placa whisky xis yankee três zulu dois seis", "WXY3Z26"),
        ok("consultar placa juliet kilo lima cinco mike sete quatro", "JKL5M74"),
        ok("verificar placa alfa alfa alfa zero alfa zero zero", "AAA0A00"),
    )

    /**
     * As mesmas ditadas, agora com o erro do whisper.
     *
     * Vogal final trocada ("tangu"), consoante comida, palavra estrangeira
     * aportuguesada. É o caso real: o modelo transcreve pt-BR e o alfabeto tem
     * palavra de outra língua.
     */
    val foneticasCorrompidas = listOf(
        ok("Claryon, consultar placa tangu bravu unido três delta sete zero.", "TBU3D70"),
        ok("verificar placa alfa bravu charli um dois três quatro", "ABC1234"),
        ok("consultar placa romeo india oscar sete alfa dois um", "RIO7A21"),
        ok("rodar placa siera papa oscar nove quilo zero zero", "SPO9K00"),
        ok("checar placa lima uniform zulu meia bravo zero um", "LUZ6B01"),
        ok("consultar placa golfe oscar lima quatro delta meia sete", "GOL4D67"),
        ok("verificar placa maike november papa oito hotél quatro cinco", "MNP8H45"),
        ok("consultar placa eco delta unido dois foxtrote nove nove", "EDU2F99"),
        ok("rodar placa quebeque romeu serra zero tangô um oito", "QRS0T18"),
        ok("checar placa uísque xis ianque três zulu dois seis", "WXY3Z26"),
    )

    /**
     * Placa dita **sem** alfabeto: soletrada letra a letra, ou com o número por
     * extenso, que é como um agente fala quando o canal está limpo.
     */
    val semAlfabeto = listOf(
        ok("consultar placa ABC mil duzentos e trinta e quatro", "ABC1234"),
        ok("verificar placa ABC 1D23", "ABC1D23"),
        ok("Claryon, consultar placa ABC-1234.", "ABC1234"),
        ok("checar placa a bê cê um dois três quatro", "ABC1234"),
        ok("rodar placa QWE mil e vinte e três", "QWE1023"),
        ok("consultar placa BRA dois E dezenove", "BRA2E19"),
        ok("verificar placa RIO 2 A 18", "RIO2A18"),
        ok("consultar placa FGH nove sete meia dois", "FGH9762"),
        ok("checar placa JKL zero zero zero um", "JKL0001"),
        ok("rodar placa PQR quatro S cinco seis", "PQR4S56"),
    )

    /**
     * **Os algarismos ORDINAIS — o jeito que a PM manda ditar, e que eu não sabia.**
     *
     * A Portaria 071-CG/15 da PM da Bahia traz a ditada de `5448` como *"Quinto;
     * Quarto Dobrado; Oitavo"*. O primeiro caso abaixo é essa sequência, com as três
     * letras que a acompanham no próprio texto da norma.
     *
     * Os últimos são o sistema concorrente da PMERJ, com `uno`, `meia` e o
     * multiplicador **antes** do algarismo.
     */
    val ordinaisERepetidores = listOf(
        // "JNO – 5448": o exemplo textual da norma, virado placa de sete.
        ok("consultar placa juliet november oscar quinto quarto dobrado oitavo", "JNO5448"),
        ok("verificar placa alfa bravo charlie primeiro segundo terceiro quarto", "ABC1234"),
        ok("checar placa alfa bravo charlie quinto negativo negativo primeiro", "ABC5001"),
        ok("rodar placa lima uniforme zulu sexto bravo negativo primeiro", "LUZ6B01"),
        // PMERJ: multiplicador ANTES.
        ok("consultar placa alfa bravo charlie uno duplo zero sete", "ABC1007"),
        ok("verificar placa triplo alfa negativo alfa duplo zero", "AAA0A00"),
        ok("consultar placa golf oscar lima quatro delta meia sete", "GOL4D67"),
        ok("checar placa tango bravo uniforme terceiro delta setimo negativo", "TBU3D70"),
    )

    /**
     * **Fala que NÃO é placa.** Cada uma tem de ser recusada.
     *
     * As três primeiras são as do enunciado; o resto é vocabulário de rádio e de
     * abordagem escolhido para ser **adversarial**: contém palavra do alfabeto
     * ("papa", "lima", "serra", "delta"), contém número, ou as duas coisas.
     */
    val negativas = listOf(
        nao("Claryon, vou verificar a placa depois."),
        nao("a placa do carro tá suja"),
        nao("Claryon, consultar placa."),
        nao("verificar placa do veículo que passou agora"),
        nao("a placa tá ilegível daqui"),
        nao("consultar placa de moto, não deu pra ver o número"),
        nao("Claryon, a placa não bate com o documento."),
        nao("checar placa mas o carro já saiu"),
        nao("placa dianteira arrancada, traseira intacta"),
        nao("rodar placa desse Golf preto na esquina"),
        nao("a placa é de outro estado, acho que é do Rio"),
        nao("consultar placa amanhã de manhã, agora não dá"),
        nao("tem uma lima e um papa na viatura"),
        nao("placa parcialmente coberta por lama"),
        nao("Claryon, verificar placa: não consegui ler nada."),
        nao("a placa some atrás do para-choque"),
        nao("veículo sem placa, abordagem em andamento"),
        nao("placa de papel colada no vidro"),
        nao("consultar placa do carro da serra, subiu pela delta"),
        nao("Claryon, a placa tem três letras e quatro números."),
        nao("placa velha, dessas antigas cinza"),
        nao("verificar placa, câmbio"),
        // Adversariais para os ORDINAIS: são palavras corriqueiras de rádio.
        nao("consultar placa, é o segundo carro da fila"),
        nao("verificar placa do quarto veículo, o primeiro já passou"),
        nao("Claryon, negativo, a placa não confere."),
        nao("checar placa em segundo plano, prioridade é o apoio"),
        nao("placa do terceiro andar do estacionamento, quinto box"),
        nao("consultar placa, negativo negativo, não deu pra ver"),
    )

    /**
     * **Placas inventadas: sete símbolos na ordem errada.**
     *
     * Este é o lado que a gramática existe para pegar. Se a validação sumir, TODAS
     * estas passam a ser aceitas — e é isso que o contra-teste prova.
     */
    val gramaticaErrada = listOf(
        nao("consultar placa alfa bravo um dois três quatro cinco"),   // AB12345
        nao("verificar placa um dois três alfa bravo charlie delta"),  // 123ABCD
        nao("checar placa alfa bravo charlie delta um dois três"),     // ABCD123
        nao("rodar placa um alfa bravo charlie dois três quatro"),     // 1ABC234
        nao("consultar placa alfa um bravo dois charlie três quatro"), // A1B2C34
        nao("verificar placa alfa bravo charlie delta echo foxtrot golf"), // ABCDEFG
        nao("consultar placa um dois três quatro cinco seis sete"),    // 1234567
        nao("checar placa alfa bravo charlie um delta dois echo"),     // ABC1D2E
    )

    /**
     * **Contagem errada: seis, oito, nove símbolos.** Descartadas inteiras, sem
     * procurar uma placa boa lá dentro.
     */
    val contagemErrada = listOf(
        nao("consultar placa alfa bravo charlie um dois três"),                    // 6
        nao("verificar placa alfa bravo charlie um dois três quatro cinco"),       // 8
        nao("checar placa alfa bravo charlie delta um dois três quatro cinco"),    // 9
        nao("consultar placa ABC12345"),
        nao("verificar placa 12ABC1234"),
        nao("rodar placa ABC1D234"),
        nao("consultar placa tango bravo unido três delta sete"),                  // 6
        nao("checar placa tango bravo unido três delta sete zero nove"),           // 8
    )

    val positivos: List<Caso> =
        foneticasLimpas + foneticasCorrompidas + semAlfabeto + ordinaisERepetidores

    /** Tudo que **não** pode virar placa: fala corrente, forma errada, contagem errada. */
    val negativos: List<Caso> = negativas + gramaticaErrada + contagemErrada

    val banco: List<Caso> = positivos + negativos
}
