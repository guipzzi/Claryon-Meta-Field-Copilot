package com.claryon.audio

/**
 * **Rota falsa para teste — existe apenas no build de depuração.**
 *
 * ## Por que isto não é um furo no controle de compliance
 *
 * `GlassesAudioRoute` existe para tornar impossível, por construção, capturar
 * pelo microfone omnidirecional do celular: o único jeito de obter uma instância
 * é passar por `acquire`, que confere o dispositivo de comunicação ativo. Abrir
 * uma fábrica pública em `main` desfaria exatamente isso — `app/src/main` passaria
 * a poder fabricar a prova, e a frase "gravar pelo microfone do celular deixa de
 * compilar" viraria falsa no módulo onde vivem todos os caminhos de captura.
 *
 * Este arquivo mora em `src/debug`. Consequência concreta, verificável por
 * `./gradlew assembleRelease`: **no APK de release esta função não existe**, e
 * qualquer código de produção que a chamasse quebraria o build de release em vez
 * de embarcar. A garantia continua sendo do compilador, não de disciplina.
 *
 * É a mesma classe de proteção que `GlassesAudioRoute.acquireParaDesenvolvimento`
 * já usa com `buildDebug`, um nível acima: lá o guarda é de execução, aqui é de
 * compilação.
 *
 * @param deviceId qualquer valor; só precisa ser consistente dentro do teste,
 *   porque `confereRota` compara por igualdade de id.
 */
fun rotaDeTeste(deviceId: Int = 1): GlassesAudioRoute =
    GlassesAudioRoute.fabricarSemRotear(deviceId)
