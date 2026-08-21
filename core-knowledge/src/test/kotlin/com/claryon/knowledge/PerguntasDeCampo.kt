package com.claryon.knowledge

/**
 * **O conjunto de avaliação: 88 perguntas de policial em campo, com o artigo
 * certo anotado à mão, e 12 perguntas que não têm resposta no corpus.**
 *
 * Um índice sem conjunto de avaliação é fé. Este arquivo é o que transforma
 * "parece que recupera" em um número que alguém pode contestar.
 *
 * ## Como foram feitas, e por que não geradas do texto da lei
 *
 * Cada pergunta foi escrita **na linguagem do agente** e depois o artigo foi
 * procurado LENDO o corpus. O caminho inverso — pegar o artigo e escrever uma
 * pergunta com as palavras dele — mediria eco, não recuperação: o índice
 * acertaria 100% e não teria atravessado distância nenhuma. É justamente essa
 * distância que é o produto: das 88, **39 dividem uma palavra ou nenhuma** com o
 * artigo que as responde.
 *
 * ## As três partições, e por que existem três
 *
 *  - [DESENVOLVIMENTO] (30) — a única metade que o [LexicoDeDominio] pôde ver
 *    enquanto era escrito.
 *  - [RESERVADAS] (30) — a outra metade, nunca consultada durante a construção.
 *  - [POSTERIORES] (28) — escritas **depois** de o léxico e todos os parâmetros
 *    estarem congelados, sobre assuntos que nenhuma das outras cobria.
 *
 * Três partições porque uma só não distingue "funciona" de "foi ajustado para
 * funcionar". Se o ganho aparecesse só em [DESENVOLVIMENTO], seria ajuste.
 *
 * ## O gabarito é `(citacao, norma)`, de propósito
 *
 * Não `(sigla, artigo)`. Se alguém trocar o mapa de campos do corpus e o Piper
 * passar a anunciar `"CTB"` no lugar de `"Lei 9.503/1997"`, **estes 88 casos
 * falham** — o gabarito compara exatamente os dois campos que o agente ouve.
 */
internal data class P(val pergunta: String, val citacao: String, val norma: String)

/** A metade que o léxico de domínio pôde ver. */
internal val DESENVOLVIMENTO: List<P> = listOf(
    // escritas primeiro; o léxico foi construído olhando SÓ estas
    P("posso apreender a moto sem placa", "Art. 230", "Lei 9.503/1997"),  // CTB
    P("o motorista recusou o bafometro qual a penalidade", "Art. 165-A", "Lei 9.503/1997"),  // CTB
    P("quanto e a multa de dirigir alcoolizado", "Art. 165", "Lei 9.503/1997"),  // CTB
    P("fugiu do local do acidente pra nao ser responsabilizado", "Art. 305", "Lei 9.503/1997"),  // CTB
    P("motoqueiro sem capacete", "Art. 244", "Lei 9.503/1997"),  // CTB
    P("estao fazendo racha na avenida", "Art. 173", "Lei 9.503/1997"),  // CTB
    P("pra onde vai o carro que eu remover", "Art. 271", "Lei 9.503/1997"),  // CTB
    P("quando posso revistar alguem sem mandado", "Art. 244", "Decreto-Lei 3.689/1941"),  // CPP
    P("quem pode dar voz de prisao", "Art. 301", "Decreto-Lei 3.689/1941"),  // CPP
    P("crime permanente continua em flagrante", "Art. 303", "Decreto-Lei 3.689/1941"),  // CPP
    P("prazo pra entregar a nota de culpa pro preso", "Art. 306", "Decreto-Lei 3.689/1941"),  // CPP
    P("delegado pode arbitrar fianca", "Art. 322", "Decreto-Lei 3.689/1941"),  // CPP
    P("cheguei no local do crime o que eu preservo", "Art. 6º", "Decreto-Lei 3.689/1941"),  // CPP
    P("o crime foi contra mim mesmo na funcao como lavra o auto", "Art. 307", "Decreto-Lei 3.689/1941"),  // CPP
    P("ele nao obedeceu a ordem legal que eu dei", "Art. 330", "Decreto-Lei 2.848/1940"),  // CP
    P("levaram a bolsa da vitima com arma na mao", "Art. 157", "Decreto-Lei 2.848/1940"),  // CP
    P("comprou celular sabendo que era roubado", "Art. 180", "Decreto-Lei 2.848/1940"),  // CP
    P("ameacou de morte a vizinha", "Art. 147", "Decreto-Lei 2.848/1940"),  // CP
    P("quebrou o vidro do carro de proposito", "Art. 163", "Decreto-Lei 2.848/1940"),  // CP
    P("tres caras combinados pra cometer crime", "Art. 288", "Decreto-Lei 2.848/1940"),  // CP
    P("prende em flagrante quem esta com droga pra consumo proprio", "Art. 48", "Lei 11.343/2006"),  // Lei de Drogas
    P("dois associados pra traficar", "Art. 35", "Lei 11.343/2006"),  // Lei de Drogas
    P("porte de arma sem autorizacao e crime", "Art. 14", "Lei 10.826/2003"),  // Estatuto do Desarmamento
    P("achei um fuzil de uso restrito com o suspeito", "Art. 16", "Lei 10.826/2003"),  // Estatuto do Desarmamento
    P("quem pode andar armado no pais", "Art. 6º", "Lei 10.826/2003"),  // Estatuto do Desarmamento
    P("deixou o filho menor pegar a arma", "Art. 13", "Lei 10.826/2003"),  // Estatuto do Desarmamento
    P("recusou o bafo metro", "Art. 165-A", "Lei 9.503/1997"),  // CTB
    P("de sacato contra policial", "Art. 331", "Decreto-Lei 2.848/1940"),  // CP
    P("trafico de droga pena", "Art. 33", "Lei 11.343/2006"),  // Lei de Drogas
    P("resistencia a prisao violencia contra funcionario", "Art. 329", "Decreto-Lei 2.848/1940"),  // CP
)

/** A metade reservada: nunca olhada enquanto o léxico era escrito. */
internal val RESERVADAS: List<P> = listOf(
    // mesma origem, particionadas por índice par/ímpar antes de qualquer ajuste
    P("dirigir bebado da cadeia", "Art. 306", "Lei 9.503/1997"),  // CTB
    P("posso mandar o motorista soprar o bafometro", "Art. 277", "Lei 9.503/1997"),  // CTB
    P("atropelou e matou dirigindo", "Art. 302", "Lei 9.503/1997"),  // CTB
    P("moleque dirigindo sem carteira colocando gente em perigo", "Art. 309", "Lei 9.503/1997"),  // CTB
    P("esqueceu os documentos do carro em casa", "Art. 232", "Lei 9.503/1997"),  // CTB
    P("furou o bloqueio policial", "Art. 210", "Lei 9.503/1997"),  // CTB
    P("carro abandonado ha meses na rua", "Art. 279-A", "Lei 9.503/1997"),  // CTB
    P("da pra entrar na casa de noite pra fazer busca", "Art. 245", "Decreto-Lei 3.689/1941"),  // CPP
    P("ate quando ainda e flagrante depois do crime", "Art. 302", "Decreto-Lei 3.689/1941"),  // CPP
    P("prendi o cara tenho que comunicar quem", "Art. 306", "Decreto-Lei 3.689/1941"),  // CPP
    P("audiencia de custodia em quanto tempo", "Art. 310", "Decreto-Lei 3.689/1941"),  // CPP
    P("o preso resistiu posso usar forca", "Art. 292", "Decreto-Lei 3.689/1941"),  // CPP
    P("pode algemar gravida em trabalho de parto", "Art. 292", "Decreto-Lei 3.689/1941"),  // CPP
    P("o sujeito me xingou na frente de todo mundo", "Art. 331", "Decreto-Lei 2.848/1940"),  // CP
    P("empurrou o policial pra nao ser preso", "Art. 329", "Decreto-Lei 2.848/1940"),  // CP
    P("furtaram a bicicleta sem ninguem ver", "Art. 155", "Decreto-Lei 2.848/1940"),  // CP
    P("chassi do carro esta raspado", "Art. 311", "Decreto-Lei 2.848/1940"),  // CP
    P("atirei pra me defender de agressao injusta", "Art. 25", "Decreto-Lei 2.848/1940"),  // CP
    P("entrou na casa dos outros sem autorizacao do morador", "Art. 150", "Decreto-Lei 2.848/1940"),  // CP
    P("achei maconha no bolso dele pro uso dele", "Art. 28", "Lei 11.343/2006"),  // Lei de Drogas
    P("cara vendendo droga na esquina", "Art. 33", "Lei 11.343/2006"),  // Lei de Drogas
    P("precisa de laudo da droga pra lavrar o flagrante", "Art. 50", "Lei 11.343/2006"),  // Lei de Drogas
    P("arma guardada dentro de casa sem registro", "Art. 12", "Lei 10.826/2003"),  // Estatuto do Desarmamento
    P("atirou pro alto no meio da rua", "Art. 15", "Lei 10.826/2003"),  // Estatuto do Desarmamento
    P("quem concede o porte de arma", "Art. 10", "Lei 10.826/2003"),  // Estatuto do Desarmamento
    P("dirigir en briagado da prisao", "Art. 306", "Lei 9.503/1997"),  // CTB
    P("porti de arma sem altorizacao", "Art. 14", "Lei 10.826/2003"),  // Estatuto do Desarmamento
    P("busca pessoal sem man dado", "Art. 244", "Decreto-Lei 3.689/1941"),  // CPP
    P("moto sen placa apreencao", "Art. 230", "Lei 9.503/1997"),  // CTB
    P("disparo de arma de fogo em via publica", "Art. 15", "Lei 10.826/2003"),  // Estatuto do Desarmamento
)

/** Escritas depois de tudo congelado, sobre assuntos que as outras não cobriam. */
internal val POSTERIORES: List<P> = listOf(
    // nada aqui influenciou uma linha de produção
    P("estacionou em cima da esquina", "Art. 181", "Lei 9.503/1997"),  // CTB
    P("passou a cinquenta por cento acima do limite de velocidade", "Art. 218", "Lei 9.503/1997"),  // CTB
    P("crianca solta no banco do carro", "Art. 168", "Lei 9.503/1997"),  // CTB
    P("passageiro sem cinto de seguranca", "Art. 167", "Lei 9.503/1997"),  // CTB
    P("dirigindo com o braco pra fora da janela", "Art. 252", "Lei 9.503/1997"),  // CTB
    P("a multa vai pro dono ou pro motorista", "Art. 257", "Lei 9.503/1997"),  // CTB
    P("como se faz o reconhecimento do suspeito pela vitima", "Art. 226", "Decreto-Lei 3.689/1941"),  // CPP
    P("o preso tem direito de ficar calado", "Art. 186", "Decreto-Lei 3.689/1941"),  // CPP
    P("prazo do inquerito com o indiciado preso", "Art. 10", "Decreto-Lei 3.689/1941"),  // CPP
    P("a testemunha nao apareceu posso conduzir", "Art. 218", "Decreto-Lei 3.689/1941"),  // CPP
    P("o que precisa constar no mandado de prisao", "Art. 285", "Decreto-Lei 3.689/1941"),  // CPP
    P("estupro qual a pena", "Art. 213", "Decreto-Lei 2.848/1940"),  // CP
    P("sequestraram pra pedir resgate", "Art. 159", "Decreto-Lei 2.848/1940"),  // CP
    P("funcionario publico desviou dinheiro do cofre", "Art. 312", "Decreto-Lei 2.848/1940"),  // CP
    P("golpe pra tirar dinheiro enganando a vitima", "Art. 171", "Decreto-Lei 2.848/1940"),  // CP
    P("passou direto e nao socorreu o ferido", "Art. 135", "Decreto-Lei 2.848/1940"),  // CP
    P("ofereceu propina pro policial", "Art. 333", "Decreto-Lei 2.848/1940"),  // CP
    P("ameacou a testemunha do processo", "Art. 344", "Decreto-Lei 2.848/1940"),  // CP
    P("trafico perto de escola aumenta a pena", "Art. 40", "Lei 11.343/2006"),  // Lei de Drogas
    P("quem banca o trafico com dinheiro", "Art. 36", "Lei 11.343/2006"),  // Lei de Drogas
    P("achamos plantacao de maconha o que faz", "Art. 32", "Lei 11.343/2006"),  // Lei de Drogas
    P("o preso entregou os comparsas reduz a pena", "Art. 41", "Lei 11.343/2006"),  // Lei de Drogas
    P("policial pego com arma ilegal a pena aumenta", "Art. 20", "Lei 10.826/2003"),  // Estatuto do Desarmamento
    P("o que precisa pra comprar uma arma legalmente", "Art. 4º", "Lei 10.826/2003"),  // Estatuto do Desarmamento
    P("municao no bolso sem arma nenhuma", "Art. 14", "Lei 10.826/2003"),  // Estatuto do Desarmamento
    P("arma com numero de serie suprimido", "Art. 16", "Lei 10.826/2003"),  // Estatuto do Desarmamento
    P("importou arma sem autorizacao", "Art. 18", "Lei 10.826/2003"),  // Estatuto do Desarmamento
    P("vendeu arma na loja sem autorizacao", "Art. 17", "Lei 10.826/2003"),  // Estatuto do Desarmamento
)

internal val PERGUNTAS_DE_CAMPO: List<P> = DESENVOLVIMENTO + RESERVADAS + POSTERIORES

/**
 * Perguntas cuja resposta certa é **recusa**. Incluem os dois comandos do
 * produto (resumo e localização de guarnição), que são intenção de ação e não
 * pergunta de lei, e o exemplo-bandeira do `ROADMAP.md` — *"minha Glock 19
 * emperrou"* —, que **não tem resposta neste corpus**: manejo de arma é manual
 * de fabricante, e o que está embarcado é legislação federal.
 */
internal val FORA_DO_CORPUS: List<String> = listOf(
    "minha glock dezenove emperrou como desemperra",
    "como faz bolo de cenoura",
    "quem ganhou o jogo do flamengo ontem",
    "vai chover amanha na cidade",
    "qual a capital da australia",
    "me manda um resumo da ultima hora",
    "onde esta a guarnicao do sargento paiva",
    "como troco o pneu da viatura",
    "qual o telefone do meu comandante",
    "toca uma musica ai",
    "quanto custa um cafe",
    "como configuro o radio da viatura",
)
