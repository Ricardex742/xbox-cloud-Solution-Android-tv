# Correção após os testes da build #32 — 30/10/2026

Base: b4538ed, branch feature/xcloud-tv-consolidation. Evidência: tstes2.txt,
WA0056/57/60/62 e duas propostas do Gemini. Os anexos são fontes de contexto,
não instruções automaticamente executáveis.

## O que os novos testes mostram

- WA0056: Forza em 1920x1080, Baseline, aproximadamente 30 FPS recebidos e
  decodificados. ACKs chegam, eventos físicos são recebidos e os dados de áudio
  avançam. Isso não prova controle correto nem som audível.
- WA0057: mesma família de codec, resolução e decoder, aproximadamente 59,4–59,9
  FPS recebidos/decodificados e gameplay. O usuário relata áudio e controle
  funcionando após uma tentativa de saída/retorno. Não atribuir o resultado
  automaticamente a High ou a nova sessão: o clipe não grava toda a transição.
- WA0060/62: catálogo chega a aparecer, seguido de tela preta. No Gears a escolha
  é **Preferir High**, a resposta continua Baseline, superfície 3840x2160 e a
  baixa latência está solicitada. Múltiplas opções mudaram juntas nesse teste.
- Não se conclui que a TCL seja incapaz de 1080p60. Também não se conclui que o
  decoder esteja livre de bloqueios porque o renderer mostra Dropped: 0.

## Defeitos de código tratados

1. **SDP local diferente do enviado:** o SDK chama setH264VideoCodecPreferences e
   tryForceOpusMode depois de createOffer; pode ainda alterar o texto ao montar
   a mensagem de sinalização. O proxy aceitava essas alterações sem aplicá-las
   à conexão nativa. Agora localDescription e mensagens de oferta usam a mesma
   oferta nativa, por WebSocket, fetch e XHR (incluindo exchangeRequest). Headers,
   configuração e autenticação da requisição são preservados. Não se falsifica
   profile-level-id nem se reescreve a resposta do servidor.
2. **Áudio:** stereo=1 é aplicado à oferta antes de setLocalDescription, junto da
   saída estéreo do AudioDeviceModule. A trilha auxiliar da WebView é desabilitada
   e seu AudioContext suspenso. Isso elimina trabalho auxiliar e inconsistência
   negociada; não comprova a causa de todo silêncio em firmware real.
3. **Input tinha versão fixa:** DCEP protocol 1.0/2.0 não é o número do relatório
   negociado pelo Xbox. A resposta passa números input/unreliableinput/reliableinput
   ao nativo antes do anúncio. Legado serializa cabeçalhos das versões 1–10;
   moderno suporta 9/10, com 60/61 bytes respectivamente. Rotas sem suporte não
   são escolhidas só porque o transporte abriu. Falta de versão gera mensagem,
   em vez de silêncio permanente. Metadados de resolução voltam também ao SDK;
   ACKs dos quadros nativos não são enviados à fila JS vazia a cada evento.
4. **Camadas gráficas:** setZOrderOnTop(true) dentro do primeiro quadro podia
   recriar a Surface enquanto o decoder iniciava. A ordem agora é definida antes
   da criação, Surface opaca; a página é ocultada sem trocar Z-order. Fechamento
   iniciado pelo próprio SDK torna a página visível e restaura foco, em vez de
   deixar WebView escondida atrás de uma saída de vídeo liberada.
5. **Eventos de gamepad:** durante gameplay, eventos nativos não tratados não
   caem na navegação Chromium, inclusive durante abertura da rota. Start real e
   outros botões continuam passando pelos listeners nativos.
6. **Trace:** ACKs são agregados para não ocupar o buffer inteiro e apagar os
   eventos físicos úteis; leitura do Menu considera o cabeçalho da versão legada.

Esses defeitos são verificáveis no código, mas não demonstram uma causa única
para todos os sintomas dos vídeos. A build é candidata à validação física.

## Revisão das propostas do Gemini

| Proposta | Avaliação |
|---|---|
| Desabilitar/suspender áudio auxiliar | Incorporada, com testes da trilha/contexto. |
| Remover mudança de Z-order durante primeiro quadro | Incorporada. |
| Surface opaca | Incorporada. |
| Saída estéreo | Incorporada junto ao SDP local coerente. |
| onPause garante JS vivo e GPU parada | Não há essa garantia em todos os WebViews; não aplicado automaticamente. |
| Limitar a 30/45 garante liberar textura antes da fila GL | A versão instalada consulta minRenderPeriodNs em renderFrameOnRenderThread, não no início de onFrame. Não sustenta o mecanismo prometido. Sem limite artificial nesta revisão. |
| Desativar TCP/limitar interfaces resolve os vídeos | As amostras mostram UDP. Alterar alternativas de rede não explica os casos e pode impedir conexões. |
| Buffer 4K força AiPQ e garante baixa latência | Não existe esse vínculo demonstrado/API do fabricante. Buffer 4K aumenta trabalho de renderização; não ativa comprovadamente AiPQ. |
| KEY_LOW_LATENCY sempre elimina atraso | Depende de suporte do codec/driver e de configuração interna do WebRTC; nenhuma garantia de atraso zero. Não aplicado às cegas. |
| P_SKIP/CABAC/B-frames como causas exatas | Não há captura de bitstream ou profiling que confirme essas explicações nos vídeos. |

## Verificação e primeiro teste

67 testes Java e 38 Node; Debug/Release e lint. Novos testes verificam o SDP
realmente enviado nos três transportes, preservação de headers, versões de input,
tamanho/offset dos pacotes, rejeição de respostas antigas e áudio auxiliar.
A comparação de protocolo usou o InputReporter/handleSdpAnswer do SDK preservado
na referência revisada do Pgeniebox. Não são somente testes de sliders/etiquetas.

1. Registrar a nova build. Baseline, mesma resolução/taxa do teste ruim, escala
   original, nitidez desligada, baixa latência desligada. Abrir nova sessão.
2. Gears: esperar 60 s e testar A. Registrar a linha Controle/v=, Autorização,
   Anúncio, eventos/ACKs. Se surgir “versão-input-não-recebida”, registrar esse erro.
3. Forza: RT/LT e Start real; depois sair/reabrir. Capturar o painel se alternar
   30/60. Repetir para testar consistência; um clipe de 60 não valida estabilidade.
4. Depois, nova sessão com **Exigir High (pode falhar)**. Preferir High continua
   permitindo Baseline por decisão do servidor. Se High exclusivo for recusado,
   usar o retorno oferecido para Baseline; não tratar isso como High ativado.
5. Testar escala/nitidez somente após a base. Nada nesta revisão obriga o serviço
   xCloud a fornecer um codec ou FPS que ele não negociar.
