// JNI do redator: llama.cpp visto pelo Kotlin como quatro funcoes e nada mais.
//
// **Este arquivo nao decide nada de produto.** Ele nao sabe o que e norma, nao
// sabe o que e intencao e nao conhece executor. Ele recebe duas strings (sistema
// e usuario), devolve uma string, e tem um prazo. Toda regra dura da Etapa B —
// so falar do que foi recuperado, nunca virar acao, teto de palavras — mora no
// Kotlin, onde da para testar sem NDK.
//
// Assinaturas conferidas em `llama/include/llama.h` do artefato vendorizado
// (Regra Zero), nao de memoria:
//   llama_model_load_from_file(const char*, llama_model_params)        :507
//   llama_init_from_model(llama_model*, llama_context_params)          :532
//   llama_model_get_vocab(const llama_model*)                          :576
//   llama_tokenize(vocab, text, len, tokens, max, add_special, parse)  :1163
//   llama_token_to_piece(vocab, token, buf, len, lstrip, special)      :1177
//   llama_chat_apply_template(tmpl, chat, n_msg, add_ass, buf, len)    :1214
//   llama_batch_get_one(llama_token*, int32_t)                         :940
//   llama_decode(ctx, batch)                                           :981
//   llama_sampler_sample(smpl, ctx, idx)  — ja faz o accept            :1531
//   llama_vocab_is_eog(vocab, token)                                   :1096
//   llama_set_abort_callback(ctx, cb, data)                            :1012
//   llama_memory_clear(llama_get_memory(ctx), data)                    :730
#include <jni.h>

#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "ClaryonField"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// O log do llama.cpp vai para o MESMO `adb logcat -s ClaryonField` que o resto
// do projeto usa. Sem isto, uma falha de carga de GGUF sai em stderr e some.
void redirecionar_log(ggml_log_level nivel, const char * texto, void *) {
    if (texto == nullptr || texto[0] == '\0') return;
    const int prioridade = nivel >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
                         : nivel >= GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
                                                         : ANDROID_LOG_DEBUG;
    __android_log_print(prioridade, TAG, "llama: %s", texto);
}

std::atomic<bool> backend_iniciado{false};

/// Tudo o que uma sessao de redacao precisa. Um por modelo carregado.
struct Sessao {
    llama_model *   modelo = nullptr;
    llama_context * ctx    = nullptr;
    llama_sampler * smpl   = nullptr;

    // Prazo da geracao corrente, em relogio monotonico. O `abort_callback` do
    // llama le isto a cada no do grafo: sem ele, um modelo que resolva divagar
    // seguraria o ciclo de voz muito alem dos 4 s do aceite, e o agente ficaria
    // com o aparelho mudo no meio de uma abordagem. Prazo estourado devolve o
    // que ja foi gerado, nunca vazio-por-excecao.
    std::atomic<int64_t> prazo_ms{0};
};

int64_t agora_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

bool abortar(void * dados) {
    auto * s = static_cast<Sessao *>(dados);
    const int64_t prazo = s->prazo_ms.load(std::memory_order_relaxed);
    return prazo != 0 && agora_ms() > prazo;
}

std::string paraUtf8(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out = c == nullptr ? std::string{} : std::string(c);
    if (c != nullptr) env->ReleaseStringUTFChars(s, c);
    return out;
}

std::string pedaco(const llama_vocab * vocab, llama_token token) {
    char buf[256];
    const int32_t n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, /*special=*/false);
    if (n < 0) {
        std::vector<char> maior(static_cast<size_t>(-n));
        const int32_t m = llama_token_to_piece(
            vocab, token, maior.data(), static_cast<int32_t>(maior.size()), 0, false);
        return m <= 0 ? std::string{} : std::string(maior.data(), static_cast<size_t>(m));
    }
    return std::string(buf, static_cast<size_t>(n));
}

}  // namespace

extern "C" {

/// Carrega o GGUF de [caminho] e devolve o ponteiro da sessao, ou 0.
///
/// **Recebe CAMINHO DE ARQUIVO, e por isso o modelo nunca pode viver em
/// `assets/`.** `llama_model_load_from_file` faz `fopen`/`mmap` no sistema de
/// arquivos; asset dentro do APK nao tem caminho, comprimido ou nao. E a mesma
/// armadilha que travou o `espeak-ng-data` do Piper aqui em 2026-08.
JNIEXPORT jlong JNICALL
Java_com_claryon_llm_NativoDoRedator_nativoCarregar(
    JNIEnv * env, jobject, jstring caminho, jint n_threads, jint n_ctx) {

    if (!backend_iniciado.exchange(true)) {
        llama_log_set(redirecionar_log, nullptr);
        llama_backend_init();
    }

    const std::string arquivo = paraUtf8(env, caminho);
    if (arquivo.empty()) {
        LOGE("redator: caminho vazio");
        return 0;
    }

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;  // 100% CPU: nao ha backend de GPU compilado aqui.

    llama_model * modelo = llama_model_load_from_file(arquivo.c_str(), mp);
    if (modelo == nullptr) {
        LOGE("redator: llama_model_load_from_file devolveu null para %s", arquivo.c_str());
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = static_cast<uint32_t>(n_ctx);
    cp.n_batch         = static_cast<uint32_t>(n_ctx);
    cp.n_threads       = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf         = false;

    llama_context * ctx = llama_init_from_model(modelo, cp);
    if (ctx == nullptr) {
        LOGE("redator: llama_init_from_model devolveu null");
        llama_model_free(modelo);
        return 0;
    }

    auto * s = new Sessao();
    s->modelo = modelo;
    s->ctx    = ctx;

    // Amostragem MORNA de proposito, e nao gulosa. Guloso repete-se em laco
    // ("...o condutor. o condutor. o condutor.") quando o trecho de norma e
    // curto, e laco dentro de um teto de tempo vira resposta truncada sem
    // sentido. `min_p` corta a cauda absurda melhor que `top_p` em modelo
    // pequeno; `penalties` mata a repeticao literal.
    //
    // `llama_sampler_init_penalties` recebe CINCO parametros, e o primeiro e
    // `n_vocab` (llama.h:1445). Escrevi quatro de memoria e o cabecalho do
    // artefato corrigiu antes de virar codigo — Regra Zero em acao.
    //
    // Semente FIXA e nao `LLAMA_DEFAULT_SEED`: com semente aleatoria, medir
    // tempo de geracao duas vezes daria textos de tamanhos diferentes, e a
    // comparacao entre execucoes deixaria de significar alguma coisa.
    const llama_vocab * vocab_amostra = llama_model_get_vocab(modelo);
    llama_sampler_chain_params scp = llama_sampler_chain_default_params();
    scp.no_perf = false;
    s->smpl = llama_sampler_chain_init(scp);
    llama_sampler_chain_add(s->smpl, llama_sampler_init_penalties(
                                         llama_vocab_n_tokens(vocab_amostra),
                                         /*penalty_last_n=*/64, /*penalty_repeat=*/1.1f,
                                         /*penalty_freq=*/0.0f, /*penalty_present=*/0.0f));
    llama_sampler_chain_add(s->smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(s->smpl, llama_sampler_init_temp(0.3f));
    llama_sampler_chain_add(s->smpl, llama_sampler_init_dist(/*seed=*/1234u));

    llama_set_abort_callback(ctx, abortar, s);

    LOGI("redator: modelo carregado (%s), %.1f MiB, ctx=%d, threads=%d",
         arquivo.c_str(),
         static_cast<double>(llama_model_size(modelo)) / (1024.0 * 1024.0),
         static_cast<int>(llama_n_ctx(ctx)), static_cast<int>(n_threads));
    return reinterpret_cast<jlong>(s);
}

/// Formata [sistema] + [usuario] pelo template de chat do PROPRIO modelo e gera
/// no maximo [max_tokens], parando em EOG ou no prazo de [prazo_ms].
///
/// Devolve `null` so quando nao houve como gerar nada; string vazia e resposta
/// legitima e o Kotlin a trata como recusa.
JNIEXPORT jstring JNICALL
Java_com_claryon_llm_NativoDoRedator_nativoRedigir(
    JNIEnv * env, jobject, jlong handle, jstring sistema, jstring usuario,
    jint max_tokens, jint prazo_ms) {

    auto * s = reinterpret_cast<Sessao *>(handle);
    if (s == nullptr) return nullptr;

    const std::string txt_sistema = paraUtf8(env, sistema);
    const std::string txt_usuario = paraUtf8(env, usuario);

    // **O template vem do modelo, nao de uma string escrita aqui.** Prompt no
    // formato errado nao da erro: da resposta pior, em silencio — que e o modo
    // de falha mais caro deste projeto.
    const char * tmpl = llama_model_chat_template(s->modelo, /*name=*/nullptr);

    llama_chat_message msgs[2] = {
        {"system", txt_sistema.c_str()},
        {"user",   txt_usuario.c_str()},
    };
    std::vector<char> buf(txt_sistema.size() * 2 + txt_usuario.size() * 2 + 1024);
    int32_t n = llama_chat_apply_template(tmpl, msgs, 2, /*add_ass=*/true,
                                          buf.data(), static_cast<int32_t>(buf.size()));
    if (n > static_cast<int32_t>(buf.size())) {
        buf.resize(static_cast<size_t>(n) + 1);
        n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(),
                                      static_cast<int32_t>(buf.size()));
    }
    std::string prompt;
    if (n <= 0) {
        // Modelo sem template embutido: cai para um formato simples em vez de
        // falhar. Degradar e melhor que mudez, e o Kotlin ainda filtra a saida.
        LOGE("redator: modelo sem chat template (n=%d) — usando formato simples", n);
        prompt = txt_sistema + "\n\n" + txt_usuario + "\n";
    } else {
        prompt.assign(buf.data(), static_cast<size_t>(n));
    }

    const llama_vocab * vocab = llama_model_get_vocab(s->modelo);

    // Contexto limpo a cada redacao: uma resposta NUNCA pode carregar o trecho
    // recuperado da pergunta anterior. Sem isto, "so fala sobre o que foi
    // recuperado" vazaria entre perguntas e ninguem veria.
    llama_memory_clear(llama_get_memory(s->ctx), /*data=*/true);

    std::vector<llama_token> tokens(prompt.size() + 64);
    int32_t n_tok = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                   tokens.data(), static_cast<int32_t>(tokens.size()),
                                   /*add_special=*/true, /*parse_special=*/true);
    if (n_tok < 0) {
        tokens.resize(static_cast<size_t>(-n_tok));
        n_tok = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                               tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    }
    if (n_tok <= 0) {
        LOGE("redator: tokenizacao devolveu %d", n_tok);
        return nullptr;
    }
    tokens.resize(static_cast<size_t>(n_tok));

    const int32_t teto = static_cast<int32_t>(llama_n_ctx(s->ctx));
    if (n_tok >= teto) {
        LOGE("redator: prompt de %d tokens nao cabe no contexto de %d", n_tok, teto);
        return nullptr;
    }

    s->prazo_ms.store(prazo_ms > 0 ? agora_ms() + prazo_ms : 0, std::memory_order_relaxed);

    const int64_t t0 = agora_ms();
    const int32_t r = llama_decode(s->ctx, llama_batch_get_one(tokens.data(), n_tok));
    if (r != 0) {
        // **Distinguir abortado de quebrado, porque a acao e diferente.** `2` e
        // o codigo de abortado (llama.h:981), e aqui ele significa uma coisa so:
        // o PRAZO acabou antes de o prompt entrar. Isso e diagnostico de
        // aparelho lento — nao de bug —, e ficou meses invisivel na primeira
        // versao deste arquivo, que logava "llama_decode falhou" para os dois.
        LOGE("redator: llama_decode devolveu %d no prompt de %d tokens apos %lld ms%s",
             r, n_tok, static_cast<long long>(agora_ms() - t0),
             r == 2 ? " (ABORTADO PELO PRAZO — aparelho lento para este modelo)" : "");
        s->prazo_ms.store(0, std::memory_order_relaxed);
        return nullptr;
    }
    const int64_t t_prompt = agora_ms() - t0;

    std::string saida;
    int32_t gerados = 0;
    const int32_t limite = max_tokens > 0 ? max_tokens : 128;
    for (; gerados < limite && n_tok + gerados < teto; ++gerados) {
        const llama_token id = llama_sampler_sample(s->smpl, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;
        saida += pedaco(vocab, id);
        llama_token proximo = id;
        if (llama_decode(s->ctx, llama_batch_get_one(&proximo, 1)) != 0) break;
        if (abortar(s)) break;
    }
    const int64_t t_total = agora_ms() - t0;
    s->prazo_ms.store(0, std::memory_order_relaxed);

    // Numero medido no aparelho, no logcat do projeto. E como o §6 do CLAUDE.md
    // pede que latencia seja afirmada: lida, nao estimada.
    LOGI("redator: prompt %d tok em %lld ms · gerou %d tok em %lld ms (total %lld ms)",
         n_tok, static_cast<long long>(t_prompt), gerados,
         static_cast<long long>(t_total - t_prompt), static_cast<long long>(t_total));

    return env->NewStringUTF(saida.c_str());
}

JNIEXPORT void JNICALL
Java_com_claryon_llm_NativoDoRedator_nativoLiberar(
    JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Sessao *>(handle);
    if (s == nullptr) return;
    if (s->smpl != nullptr) llama_sampler_free(s->smpl);
    if (s->ctx != nullptr) llama_free(s->ctx);
    if (s->modelo != nullptr) llama_model_free(s->modelo);
    delete s;
    LOGI("redator: sessao liberada");
}

/// Quantos bytes o GGUF ocupa em memoria depois de carregado. Serve a telemetria
/// e ao relatorio de custo — nao ao caminho critico.
JNIEXPORT jlong JNICALL
Java_com_claryon_llm_NativoDoRedator_nativoTamanhoDoModelo(
    JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<Sessao *>(handle);
    if (s == nullptr || s->modelo == nullptr) return 0;
    return static_cast<jlong>(llama_model_size(s->modelo));
}

}  // extern "C"
