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
//   llama_sampler_init_grammar(vocab, grammar_str, grammar_root)       :1415
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

/// **O custo da ultima geracao, decomposto.** Existe porque "2 510 ms" nao diz
/// se o tempo foi do prefill, da amostragem ou de compilar gramatica — e as tres
/// tem conserto diferente. Sem esta decomposicao, medir a Pista 2 seria comparar
/// dois totais e chutar a causa.
///
/// Escrito por `gerar()` e lido por `nativoUltimasMetricas`. Uma geracao por vez
/// por sessao: o Kotlin serializa em `Dispatchers.Default` e a bancada e
/// sequencial. Nao e estrutura concorrente e nao se pretende.
struct Metricas {
    int64_t prefill_ms    = -1;  // `llama_decode` do prompt inteiro.
    int64_t gramatica_us  = -1;  // `llama_sampler_init_grammar`. -1 = sem gramatica.
    int64_t amostragem_us = -1;  // soma de `llama_sampler_sample` na geracao.
    int64_t prompt_tok    = -1;
    int64_t gerados       = -1;
    int64_t decode_rc     = -1;  // 0 ok · 2 abortado pelo prazo · outro = quebrado.
};

/// Tudo o que uma sessao de redacao precisa. Um por modelo carregado.
struct Sessao {
    llama_model *   modelo = nullptr;
    llama_context * ctx    = nullptr;
    llama_sampler * smpl   = nullptr;

    Metricas metricas;

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

int64_t agora_us() {
    using namespace std::chrono;
    return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
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

/// Formata `sistema` + `usuario` pelo template de chat do PROPRIO modelo.
///
/// **O template vem do modelo, nao de uma string escrita aqui.** Prompt no
/// formato errado nao da erro: da resposta pior, em silencio — que e o modo de
/// falha mais caro deste projeto.
std::string montar_prompt(Sessao * s, const std::string & sistema, const std::string & usuario) {
    const char * tmpl = llama_model_chat_template(s->modelo, /*name=*/nullptr);

    llama_chat_message msgs[2] = {
        {"system", sistema.c_str()},
        {"user",   usuario.c_str()},
    };
    std::vector<char> buf(sistema.size() * 2 + usuario.size() * 2 + 1024);
    int32_t n = llama_chat_apply_template(tmpl, msgs, 2, /*add_ass=*/true,
                                          buf.data(), static_cast<int32_t>(buf.size()));
    if (n > static_cast<int32_t>(buf.size())) {
        buf.resize(static_cast<size_t>(n) + 1);
        n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(),
                                      static_cast<int32_t>(buf.size()));
    }
    if (n <= 0) {
        // Modelo sem template embutido: cai para um formato simples em vez de
        // falhar. Degradar e melhor que mudez, e o Kotlin ainda filtra a saida.
        LOGE("redator: modelo sem chat template (n=%d) — usando formato simples", n);
        return sistema + "\n\n" + usuario + "\n";
    }
    return std::string(buf.data(), static_cast<size_t>(n));
}

/// Tokeniza, faz o prefill, gera com [smpl] e preenche `s->metricas`.
///
/// Devolve `false` so quando nao houve como gerar nada — inclusive quando o
/// prazo mordeu ANTES de o prompt entrar, que e um caso distinto de "gerou
/// vazio" e vira `decode_rc == 2` nas metricas.
bool gerar(Sessao * s, const std::string & prompt, llama_sampler * smpl,
           int32_t max_tokens, int32_t prazo_ms, std::string & saida) {

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
        return false;
    }
    tokens.resize(static_cast<size_t>(n_tok));
    s->metricas.prompt_tok = n_tok;

    const int32_t teto = static_cast<int32_t>(llama_n_ctx(s->ctx));
    if (n_tok >= teto) {
        LOGE("redator: prompt de %d tokens nao cabe no contexto de %d", n_tok, teto);
        return false;
    }

    s->prazo_ms.store(prazo_ms > 0 ? agora_ms() + prazo_ms : 0, std::memory_order_relaxed);

    const int64_t t0 = agora_ms();
    const int32_t r = llama_decode(s->ctx, llama_batch_get_one(tokens.data(), n_tok));
    s->metricas.decode_rc  = r;
    s->metricas.prefill_ms = agora_ms() - t0;
    if (r != 0) {
        // **Distinguir abortado de quebrado, porque a acao e diferente.** `2` e
        // o codigo de abortado (llama.h:981), e aqui ele significa uma coisa so:
        // o PRAZO acabou antes de o prompt entrar. Isso e diagnostico de
        // aparelho lento — nao de bug —, e ficou meses invisivel na primeira
        // versao deste arquivo, que logava "llama_decode falhou" para os dois.
        LOGE("redator: llama_decode devolveu %d no prompt de %d tokens apos %lld ms%s",
             r, n_tok, static_cast<long long>(s->metricas.prefill_ms),
             r == 2 ? " (ABORTADO PELO PRAZO — aparelho lento para este modelo)" : "");
        s->prazo_ms.store(0, std::memory_order_relaxed);
        return false;
    }
    const int64_t t_prompt = s->metricas.prefill_ms;

    int64_t amostragem_us = 0;
    int32_t gerados = 0;
    const int32_t limite = max_tokens > 0 ? max_tokens : 128;
    for (; gerados < limite && n_tok + gerados < teto; ++gerados) {
        const int64_t ta = agora_us();
        const llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        amostragem_us += agora_us() - ta;
        if (llama_vocab_is_eog(vocab, id)) break;
        saida += pedaco(vocab, id);
        llama_token proximo = id;
        if (llama_decode(s->ctx, llama_batch_get_one(&proximo, 1)) != 0) break;
        if (abortar(s)) break;
    }
    const int64_t t_total = agora_ms() - t0;
    s->prazo_ms.store(0, std::memory_order_relaxed);
    s->metricas.gerados       = gerados;
    s->metricas.amostragem_us = amostragem_us;

    // Numero medido no aparelho, no logcat do projeto. E como o §6 do CLAUDE.md
    // pede que latencia seja afirmada: lida, nao estimada.
    LOGI("redator: prompt %d tok em %lld ms · gerou %d tok em %lld ms "
         "(amostragem %lld us) (total %lld ms)",
         n_tok, static_cast<long long>(t_prompt), gerados,
         static_cast<long long>(t_total - t_prompt),
         static_cast<long long>(amostragem_us), static_cast<long long>(t_total));
    return true;
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

    s->metricas = Metricas{};
    const std::string prompt = montar_prompt(s, paraUtf8(env, sistema), paraUtf8(env, usuario));

    std::string saida;
    if (!gerar(s, prompt, s->smpl, max_tokens, prazo_ms, saida)) return nullptr;
    return env->NewStringUTF(saida.c_str());
}

/// **A mesma geracao, com a cadeia de amostragem montada POR CHAMADA — e com
/// gramatica opcional.**
///
/// Existe por tres motivos, e nenhum deles e conveniencia:
///
///  1. **Gramatica e por consulta.** A da Pista 2 e derivada do TRECHO
///     recuperado, entao ela muda a cada pergunta e nao ha como montar uma so no
///     `nativoCarregar`. `llama_sampler_init_grammar` (llama.h:1415) compila GBNF
///     contra o vocabulario; o custo dessa compilacao e um dos numeros pedidos, e
///     por isso ele e cronometrado sozinho, em microssegundos.
///  2. **Cadeia nova zera o estado.** `penalties` guarda os ultimos 64 tokens e
///     `dist` carrega o RNG. Reusar a cadeia entre dois bracos de bancada faria o
///     segundo comecar de um estado que o primeiro produziu, e a diferenca medida
///     seria em parte a semente. O KDoc de `OrcamentoDaEtapaBNoAparelhoTest` ja
///     registrava isso, e pagava carregando o modelo de novo — 2,4 s por braco.
///  3. **Temperatura e penalidade viram parametro**, para que "a Pista 1 tentou
///     amostragem" seja medicao e nao alegacao.
///
/// A ORDEM da cadeia importa e nao e livre: gramatica PRIMEIRO, sobre os logits
/// crus. Depois dela vem penalidade, corte de cauda, temperatura e sorteio — a
/// mesma ordem do `common_sampler` do upstream. Gramatica depois do corte
/// poderia zerar o conjunto inteiro e o sorteio ficaria sobre `-inf`.
///
/// Gramatica invalida devolve `null` e loga: **falhar alto**. Cair em silencio
/// para a geracao livre transformaria "extracao garantida" em "extracao quando
/// deu", que e a mentira que a Pista 2 existe para nao contar.
JNIEXPORT jstring JNICALL
Java_com_claryon_llm_NativoDoRedator_nativoRedigirComOpcoes(
    JNIEnv * env, jobject, jlong handle, jstring sistema, jstring usuario, jstring gramatica,
    jint max_tokens, jint prazo_ms, jfloat temperatura, jfloat min_p, jfloat penalidade,
    jint semente) {

    auto * s = reinterpret_cast<Sessao *>(handle);
    if (s == nullptr) return nullptr;

    s->metricas = Metricas{};
    const llama_vocab * vocab = llama_model_get_vocab(s->modelo);

    llama_sampler_chain_params scp = llama_sampler_chain_default_params();
    scp.no_perf = false;
    llama_sampler * chain = llama_sampler_chain_init(scp);

    if (gramatica != nullptr) {
        const std::string gbnf = paraUtf8(env, gramatica);
        const int64_t tg = agora_us();
        llama_sampler * gram = llama_sampler_init_grammar(vocab, gbnf.c_str(), "root");
        s->metricas.gramatica_us = agora_us() - tg;
        if (gram == nullptr) {
            LOGE("redator: GBNF de %zu B nao compilou — geracao ABORTADA (nao ha queda "
                 "silenciosa para geracao livre)", gbnf.size());
            llama_sampler_free(chain);
            return nullptr;
        }
        llama_sampler_chain_add(chain, gram);
        LOGI("redator: gramatica compilada em %lld us (%zu B de GBNF)",
             static_cast<long long>(s->metricas.gramatica_us), gbnf.size());
    }

    if (penalidade > 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(
                                           llama_vocab_n_tokens(vocab),
                                           /*penalty_last_n=*/64, penalidade,
                                           /*penalty_freq=*/0.0f, /*penalty_present=*/0.0f));
    }
    if (min_p > 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_min_p(min_p, 1));
    }
    if (temperatura <= 0.0f) {
        // Guloso de verdade — e nao `temp(0)`, que divide por zero. `greedy`
        // ignora `dist`, entao ele fecha a cadeia sozinho.
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperatura));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(static_cast<uint32_t>(semente)));
    }

    const std::string prompt = montar_prompt(s, paraUtf8(env, sistema), paraUtf8(env, usuario));
    std::string saida;
    const bool ok = gerar(s, prompt, chain, max_tokens, prazo_ms, saida);
    llama_sampler_free(chain);
    if (!ok) return nullptr;
    return env->NewStringUTF(saida.c_str());
}

/// As metricas da ultima geracao desta sessao, na ordem:
/// `[prefill_ms, gramatica_us, amostragem_us, prompt_tok, gerados, decode_rc]`.
///
/// `-1` significa "nao medido nesta geracao" — `gramatica_us` fica em -1 quando
/// nao houve gramatica, e nao em 0, porque 0 seria um custo medido.
JNIEXPORT jlongArray JNICALL
Java_com_claryon_llm_NativoDoRedator_nativoUltimasMetricas(
    JNIEnv * env, jobject, jlong handle) {
    auto * s = reinterpret_cast<Sessao *>(handle);
    jlongArray fora = env->NewLongArray(6);
    if (fora == nullptr) return nullptr;
    const Metricas m = s == nullptr ? Metricas{} : s->metricas;
    jlong v[6] = {m.prefill_ms, m.gramatica_us, m.amostragem_us,
                  m.prompt_tok, m.gerados, m.decode_rc};
    env->SetLongArrayRegion(fora, 0, 6, v);
    return fora;
}

/// **Compila [gramatica] `repeticoes` vezes e devolve o total em microssegundos.**
///
/// Isolado da geracao de proposito: o custo de compilar e o numero que decide se
/// a Pista 2 cabe no orcamento, e medi-lo dentro de uma geracao o misturaria com
/// prefill e amostragem. Devolve `-1` quando o GBNF nao compila — que e resultado
/// tambem, e nao pode virar "0 us, rapidissimo".
JNIEXPORT jlong JNICALL
Java_com_claryon_llm_NativoDoRedator_nativoMedirGramatica(
    JNIEnv * env, jobject, jlong handle, jstring gramatica, jint repeticoes) {
    auto * s = reinterpret_cast<Sessao *>(handle);
    if (s == nullptr || gramatica == nullptr) return -1;
    const std::string gbnf = paraUtf8(env, gramatica);
    const llama_vocab * vocab = llama_model_get_vocab(s->modelo);
    const int32_t n = repeticoes > 0 ? repeticoes : 1;

    const int64_t t0 = agora_us();
    for (int32_t i = 0; i < n; ++i) {
        llama_sampler * g = llama_sampler_init_grammar(vocab, gbnf.c_str(), "root");
        if (g == nullptr) {
            LOGE("redator: GBNF de %zu B nao compilou na medicao", gbnf.size());
            return -1;
        }
        llama_sampler_free(g);
    }
    return agora_us() - t0;
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
