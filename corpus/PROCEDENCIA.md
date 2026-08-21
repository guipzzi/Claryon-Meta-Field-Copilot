# Corpus — procedência e licença

Baixado de `planalto.gov.br/ccivil_03` em 21/08/2026, versões **compiladas** (com as
alterações incorporadas). O `curl` sem `User-Agent` de navegador recebe resposta
vazia do servidor — está registrado aqui para ninguém concluir que o endereço mudou.

| arquivo | norma | "Art. N" encontrados |
|---|---|---|
| `ctb.html` | Lei 9.503/1997 — Código de Trânsito Brasileiro | 389 |
| `cpp.html` | Decreto-Lei 3.689/1941 — Código de Processo Penal | **805 distintos (1–811)** |
| `cp.html` | Decreto-Lei 2.848/1940 — Código Penal | 430 |
| `drogas.html` | Lei 11.343/2006 | 114 |
| `desarmamento.html` | Lei 10.826/2003 | 52 |

Todos trazem referências a alterações de **2026**, então são as versões vigentes na
data do download.

## Licença — por que pode ser embarcado

Lei 9.610/1998, art. 8º, IV: **textos de lei não são objeto de proteção autoral**.
Legislação federal pode ir dentro do APK sem autorização e sem atribuição exigida.

POP de corporação é outra coisa: documento interno, precisa de autorização de quem
é dono. Não entra aqui até alguém de lá liberar — e a arquitetura não muda por
causa disso, é acrescentar arquivo.

## O susto do CPP: era a régua, não o dado

A primeira contagem deu **242** onde o CPP tem 811 artigos, e ficou marcado como
pendência. Conferido: o download está **completo** — 805 artigos distintos, de 1 a
811, com 6 lacunas compatíveis com artigos revogados.

A contagem baixa era da minha verificação: ela não trocava `&nbsp;` por espaço, e o
`\s*` do padrão `Art\.\s*\d+` não casa com entidade HTML. Metade dos artigos usa
`&nbsp;` entre "Art." e o número.

**Fica registrado porque a lição vale para o extrator de trechos**, que é o próximo
passo: entidade HTML precisa ser resolvida ANTES de qualquer contagem ou corte. Um
extrator com o mesmo descuido produziria um corpus pela metade — e aí o copiloto
diria "não encontrei" sobre norma vigente, que é pior que não ter a norma, porque o
agente conclui que ela não se aplica.

Desconfiar do número foi certo. A causa é que era outra.
