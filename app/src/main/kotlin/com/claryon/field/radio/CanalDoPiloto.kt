package com.claryon.field.radio

/**
 * **Fonte única do canal de demonstração.**
 *
 * O mesmo UUID estava escrito em três lugares independentes — `MainActivity`,
 * `MapaViewModel` e o fallback de `RadioViewModel` — sem nenhum import cruzado.
 * O rádio e o mapa apontavam para o mesmo grupo **por digitação**, não por
 * referência: trocar um e esquecer o outro deixaria o agente falando numa
 * guarnição e vendo a posição de outra, sem nada em tela denunciando.
 *
 * Fica em `radio/` e não numa classe de constantes genérica porque quem define
 * qual é o canal é o rádio; o mapa é consumidor.
 *
 * **É provisório, e o substituto já existe.** Com a migração `0011` e
 * `RotulosFalados`, o canal passa a vir do servidor, resolvido por fala contra o
 * léxico do agente. Estas constantes são o que sustenta a demonstração até a
 * seleção por voz estar ligada de ponta a ponta — e o dia em que estiver, este
 * arquivo some inteiro. Enquanto existir, que exista **uma vez**.
 */
object CanalDoPiloto {

    /**
     * Casa com `servidor/seed_piloto.sql`. No produto vem do cadastro, e o
     * servidor já impõe o vínculo por RLS — o `agent_id` das RPCs sai do JWT.
     */
    const val ID = "22222222-0000-0000-0000-000000000001"

    /**
     * **O identificador e o nome são coisas separadas.** A consulta usa o UUID; a
     * tela mostra "GTA-3 Alfa". Mostrar o UUID vazaria chave primária para o
     * agente, e usar o nome na consulta quebraria no dia em que dois grupos se
     * chamassem igual.
     */
    const val NOME = "GTA-3 Alfa"

    /** Como a guarnição chama este canal em voz alta — casa com a migração `0011`. */
    const val ROTULO_FALADO = "guarnicao 3"
}
