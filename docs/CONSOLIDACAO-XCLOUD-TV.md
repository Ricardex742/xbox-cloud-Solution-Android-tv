# Consolidação xCloud TV — implementação de 30/10/2026

Branch: `feature/xcloud-tv-consolidation`, baseada na TCL #27 (`f10d627`).
Integra seletivamente os caminhos de inicialização, Surface e telemetria das
revisões Multilaser #30/#31. A main e as branches de teste anteriores não recebem
alterações. O aplicativo permanece dedicado ao Xbox Cloud Gaming.

## O que foi implementado

- **Sessão/catálogo:** fechamento e sinalização nativa em executor ordenado fora
  da UI; callback Binder vinculado à geração da sessão, destinatários capturados
  antes de enfileirar; proxy rejeita operações de peers encerrados. Destruição da
  página antiga sem apagar cookies. Menu acessível, encerramento com aviso após
  15 s, espera pelo primeiro quadro com aviso após 35 s e catálogo com limite de
  45 s. Após page-finished/commit-visible, verifica presença de conteúdo de
  catálogo/login; uma SPA vazia não é considerada pronta. Há tratamento de erro
  HTTP, renderizador WebView encerrado e serviço desconectado.
- **Controle:** anúncio condicionado à autorização enviada e canais abertos;
  recuperação de eventos OPEN perdidos; estado atual enviado mesmo com o controle
  parado. Nova sessão zera contadores de transição, token/tick e estados físicos;
  liberar um botão na mesma sessão continua preservando contadores. Pulso Nexus
  atrasado não altera a próxima sessão. Canal confiável de sincronização mantido.
  Correção do tamanho de rumble (11 bytes): pacote truncado não estoura buffer e
  ACK combinado começa no offset correto. ACK recebido não comprova ação no jogo.
- **Diagnóstico:** FPS recebidos/decodificados por intervalo, rede/RTT/jitter,
  idade do quadro/EGL, áudio RTP e AudioTrack, anúncio/autorização e envios locais.
  Amostragem a cada 4 s em worker quando painel aberto, uma consulta pendente.
  Painel detalhado ou compacto. Relatório visível/copiável com versão/TV/idade da
  coleta, sem cookies, access tokens ou SDP bruto. Trace opcional de controle por
  10 minutos, até 160 registros; eventos Android, gatilhos e valor Menu no pacote.
- **Vídeo:** Surface não é reenviada só por mudança de dimensões; sink antigo não
  alimenta renderer novo. Capacidade High continua baseada no decoder Android.
  Perfis Baseline, Preferir High, Automático e Somente High experimental. Este
  último filtra capacidades reais e RTX associado, sem reescrever perfil/nível;
  recusa resposta Baseline e oferece retorno manual ao catálogo com Baseline se
  falhar. Não há troca silenciosa de perfil nem garantia de aceitação pelo xCloud.
- **Qualidade:** presets TCL qualidade (1080HQ/20), TCL equilíbrio (1080/15), TL042
  compatibilidade (720/8), salvos localmente; escolhas existentes preservadas.
  Opções de escala original, até 1080p e até modo atual da tela/4K. Escala preserva
  proporção e nunca reduz a fonte; não muda o stream solicitado ao servidor.
  Nitidez leve/moderada em um passe GPU, sem readback/cópia CPU; padrão desligado.
  Falha de shader retorna ao desenho normal, com estado no painel. Isso não é
  reconstrução de detalhes nem ativação do processador proprietário da TCL.
- **Opções:** baixa latência Android opcional, suporte e solicitação distintos de
  Jogo Mestre; vibração desativável, conforme disponibilidade no controle/driver;
  microfone solicitado pelo menu, desligado a cada sessão, sem reusar sender
  descartado. Salvar ajustes avisa necessidade de nova sessão e não destrói a
  página de um jogo ativo. Menu com rolagem para caber em telas menores.

## O que não se pode declarar resolvido sem a TV

O menu inesperado do Forza, a falta de áudio e a oscilação/congelamento de vídeo
não tiveram causa única demonstrada nos anexos. Foram corrigidos defeitos reais
nas etapas relacionadas e acrescentadas medições. Não se declara estabilidade
física, negociação High ou resposta correta do Gears apenas por testes locais.

Coop local, worker/polling alternativo e perfis automáticos por jogo permanecem
investigações/opções futuras do plano, não recursos anunciados nesta build.
Não foi encontrado um caminho operacional comprovado de coop no xCloud pelo
simples uso de índices de controle. O polling alternativo não será ativado junto
com o input nativo sem evidência de ganho. Não há API TCL confirmada para forçar
Jogo Mestre em aplicativo interno.

## Verificação

- 63 testes Java e 31 Node aprovados, executando o JS de produção completo.
- `testDebugUnitTest lintDebug assembleDebug assembleRelease` com JDK17/Gradle8.13.
- Lint sem erros; avisos remanescentes constam dos relatórios do build, sem supressão.
- GLSL de nitidez compilado localmente em EGL/Mesa para variantes RGB/YUV/OES.
  Isso não testa o driver Mali/Amlogic, desempenho ou qualidade da TCL.
- Nenhum teste Android instrumentado/TV física foi executado neste ambiente.
- Workflow executa ambos os arquivos Node, testes/lint/build e assinatura/verificação
  dos três APKs ARM. COMMIT e SHA256SUMS identificam os artefatos.

## Roteiro da primeira instalação

1. Instalar o APK assinado desta branch, registrar versão/build; selecionar
   **TCL equilíbrio** e Aplicar. Manter escala original, nitidez desligada,
   Baseline, baixa latência desligada. Reabrir sessão para aplicar.
2. Gears: esperar 60 s sem input; testar A/câmera. Forza: 30 acionamentos separados
   de RT/LT e Start real; repetir moderno/legado em novas sessões.
3. Se o menu abrir sozinho, habilitar **Diagnóstico do controle (10 min)** antes
   de reproduzir. Depois abrir **Ver / copiar diagnóstico**. Filmar também as
   mãos/controle ajuda a correlacionar o evento real, sem inferi-lo só da tela.
4. Abrir painel, esperar duas amostras e registrar vídeo/áudio/controle. Se não
   houver som ou congelar, capturar o painel durante a falha; não trocar várias
   opções antes de registrar os dados. Relatório mantém idade da última coleta.
5. Sair e reabrir dez vezes entre Gears e Forza. Verificar catálogo, fim do áudio,
   menu e entrada da próxima sessão. Se o driver não retornar, o aviso orienta a
   reiniciar pelo Android; não se cria uma fila de workers para tentar contorná-lo.
6. Após a base funcionar, comparar TCL qualidade; depois Preferir High ou Somente
   High em sessão separada. Confirmar perfil efetivo. High exclusivo pode falhar.
7. Somente então comparar escala/nitidez, uma por vez, mesma cena, com o painel
   fechado para avaliar imagem e aberto separadamente para medir desempenho.
   4K amplia custo de GPU e não aumenta a resolução transmitida pelo xCloud.
8. Regressão TL042: perfil compatibilidade 720/8, mesmos testes de entrada/saída,
   controle e áudio. Não assumir que resultado da TCL valida a Multilaser.
