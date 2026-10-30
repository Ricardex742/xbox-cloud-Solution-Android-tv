# Multilaser TL042: compatibilidade e início da sessão

Data: 27/10/2026. Base: ff69b8f, da branch fix/tv-video-quality. Esta implementação fica em fix/multilaser-tl042-session; main e a branch da TCL não recebem estas mudanças.

## Evidência disponível

O usuário informou Android 11, Wi-Fi de 5 GHz e falha em todos os jogos: carregamento prolongado seguido de “desconectado da sessão de jogos da nuvem”. Não temos log dessa TV, especificação confirmada de RAM/SoC, ABI, versão do Android System WebView ou medição da rede. Android 11 atende ao minSdk 29 do aplicativo. O nome do modelo não é usado para inventar capacidades de hardware.

Os defeitos abaixo existem no código, mas ainda não demonstramos qual deles causou o erro dessa TV. A compilação e os testes de protocolo não substituem uma sessão real no xCloud.

## Brainstorm e escolhas

| Opção | Por que considerar | Decisão |
|---|---|---|
| 720p, teto 8 Mbps, preferência Baseline | Reduz resolução decodificada e tráfego frente a 1080HQ/20 Mbps | Preset inicial; disponível em qualquer instalação pelo botão TL042 |
| Preservar a WebView da sessão | Mantém mensagens, negociação e tratamento de eventos após o handshake | Implementado; vídeo continua nativo e canvas auxiliar permanece 2x2/1 fps |
| Destruir a WebView para liberar RAM | Economiza RAM, mas também remove a lógica ativa da sessão | Removido do sucesso do keepalive; recursos são liberados ao encerrar |
| Eventos entregues uma vez e mensagens de sessão completas | Elimina respostas duplicadas e mensagens descartadas durante entrada no jogo | Implementado, mantendo o input de gameplay no pipeline nativo |
| Atualizar configuração ICE sem destruir a conexão | Preserva transceivers e canais criados | Implementado; roteamento fica com Android/WebRTC, incluindo mudanças de Wi-Fi |
| Permitir candidatos TCP | Pode dar uma alternativa quando a rede não permite um caminho UDP adequado | Habilitado como candidato; não força TCP nem promete que o servidor ofereça essa rota |
| Tempo limite para operações SDP | Interrompe promessas sem resposta nativa | 30 segundos por operação, sem limitar fila/boot do jogo no servidor |
| Forçar software, 1080HQ, 20+ Mbps, High ou filtros de nitidez | Pode elevar carga/latência em hardware ainda não medido | Não ativado no preset TL042 |
| Forçar 30 FPS | Só descartar quadros localmente não reduz necessariamente a carga do decoder/rede | Não implementado sem suporte negociado e validação |

## Correções aplicadas

1. O primeiro keepalive bem-sucedido destruía a WebView. Agora ele mantém a página ativa. O heartbeat é deduplicado, cancelado ao sair/recarregar/destruir a Activity e protegido contra respostas HTTP atrasadas que tentariam reagendar uma sessão encerrada. A espera por metadados no JS é limitada e tolera dados ainda indisponíveis.
2. O canal `message` descartava todo texto que não contivesse Handshake. Mensagens normais de sessão agora são encaminhadas. `input`, `unreliableinput` e `reliableinput` continuam reservados ao pipeline nativo. Não foram alteradas deadzones nem correções de gatilhos.
3. `onmessage` era chamado por dispatchEvent e novamente pelo encaminhador. O proxy também registrava listeners simultaneamente na lista e na propriedade on*. A entrega duplicada foi removida; open/close e connectionstatechange são encaminhados; views binárias preservam offset e comprimento.
4. A resposta nativa podia chegar antes de createAnswer registrar o resolver. Ela agora fica disponível até ser consumida. setRemoteDescription só resolve após confirmação do nativo. Erros SDP atravessam AIDL, e operações sem retorno rejeitam a promessa após 30 segundos.
5. O proxy WebRTC é instalado na injeção do script, sem esperar load+300 ms. O catálogo só abre depois da inicialização do serviço nativo, evitando chamadas descartadas na partida mais lenta.
6. `setConfiguration` recriava PeerConnection e perdia canais/transceivers. Agora atualiza a configuração existente. A fábrica nativa é reutilizada dentro do serviço. Eventos nativos de conexões encerradas são filtrados por geração/identidade de canal.
7. Removida a fixação manual do processo a uma rede, cujo callback só acompanhava Ethernet. Android/WebRTC passam a acompanhar a rota disponível; coleta contínua de candidatos e candidatos TCP ficam habilitados. Recuperação após mudança de rede ainda depende do servidor e deve ser testada.
8. Se a sessão anterior liberar a saída EGL, a criação da próxima solicita novamente a Surface. As trilhas e AudioContexts auxiliares do navegador são encerrados ao fechar a sessão. Se o processo da WebView terminar, o aplicativo oferece reabrir o catálogo, sem uma sequência automática de tentativas.
9. Erros HTTP do keepalive eram classificados como sucesso. Só 2xx contam como sucesso; 401/403/404/410 encerram a renovação auxiliar e mostram aviso. Falhas transitórias tentam novamente depois de 15 segundos, com uma requisição por vez. O cliente HTTP usa a validação TLS padrão do Android, em vez de aceitar qualquer certificado. Tokens de sessão não são incluídos no log de captura.
10. O preset usa 720p, teto de 8 Mbps e modo Estável. O dispositivo anunciado no pedido 720p também passa a ter dimensões 1280x720. Quando Build.MODEL identifica TL042, apenas preferências ainda ausentes recebem esse padrão; escolhas já salvas são preservadas. Há botão explícito para instalações existentes ou modelos que se identificam com nome genérico.

## Desempenho e limites

720p tem aproximadamente 44% dos pixels de 1080p; isso não significa uma redução proporcional garantida de CPU/RAM. Manter a WebView viva custa mais memória do que destruí-la. Os testes na TV precisam verificar tanto estabilidade de sessão quanto pressão de memória. Não foram adicionados filtros de vídeo nem cópia de frames para JavaScript.

Não foi prometido 60 FPS constante ou atraso zero. TCP, se negociado, pode aumentar latência. Um bitrate máximo não garante a taxa efetivamente transmitida. Não há fallback automático para High nem reinício automático da sessão em caso de erro.

## Roteiro de instalação e validação

1. Baixar o artefato CloudX-AndroidTV-ARM da execução desta branch. Se a ABI da TV não for conhecida, começar pelo APK Universal ARM; não inferir 32/64 bits apenas pelo modelo. O APK usa o mesmo applicationId e os mesmos secrets de assinatura do projeto.
2. Na TV, abrir Configurações do aplicativo → Usar perfil Multilaser TL042 (720p / 8 Mbps) → Aplicar. Encerrar a sessão antiga e iniciar outra. Instalações existentes não têm suas preferências substituídas silenciosamente.
3. Registrar versão do app/commit, Android System WebView e horário. Manter data/hora automáticas; atualizar a WebView pela loja da TV quando houver atualização disponível. Não desabilitar validação de certificados.
4. Testar três jogos diferentes, em três sessões consecutivas, incluindo o retorno ao catálogo. Registrar se a falha acontece antes de surgir vídeo, depois do primeiro quadro ou após aproximadamente um minuto.
5. No painel, confirmar Vídeo recebido e Superfície em 1280x720, codec/decoder negociados, FPS e perdas. O primeiro intervalo de bitrate aparece como medindo; aguardar a próxima atualização.
6. Permanecer ao menos 20 minutos em uma sessão. Verificar ausência de desconexão após o primeiro keepalive, queda de frames, aquecimento, áudio sem vídeo e encerramento do processo do navegador.
7. Testar uma interrupção breve do Wi-Fi e o retorno. Se a sessão não recuperar, registrar a etapa/erro e iniciar outra; não assumir que todo servidor suporta recuperação ICE contínua.
8. Conferir rapidamente gatilhos, repouso do analógico esquerdo e resposta da câmera direita. Os arquivos dessas correções permanecem iguais aos da base.
9. Só após estabilizar o preset comparar 720p/10 Mbps. 1080p é um experimento posterior, sem mudar bitrate, codec e resolução simultaneamente.

Para diagnóstico por ADB, quando disponível: `adb logcat -s XboxFramesMain WebRtcReceiver StreamingService WebRtcBridge RTC-Codec`. Remover tokens/URLs de sessão antes de compartilhar logs. O diagnóstico precisa do erro e do momento, não das credenciais.

## Verificação automatizada

`node --test tests/session-bridge.test.cjs` exercita o código real do proxy com navegador e bridge simulados: mensagens pós-handshake, duplicação, open, conexão, confirmação remota, resposta antecipada, falhas e timeout, encerramento de recursos, keepalive, reconfiguração, bytes e dimensões 720p. Não simula o servidor nem MediaCodec.

`./gradlew --no-daemon --no-configuration-cache --max-workers=2 testDebugUnitTest lintDebug assembleDebug assembleRelease` verifica Java/AIDL/recursos, regressões anteriores e identificação do modelo. O workflow desta branch também executa os testes Node antes de assinar/publicar APKs.

## Resultado local desta implementação

- 15 testes Java e 14 testes Node aprovados, zero falhas.
- testDebugUnitTest, lintDebug, assembleDebug e assembleRelease concluídos com JDK17/Gradle8.13.
- Lint: zero erros e 93 avisos; sem nova supressão/baseline.
- JavaScript passou na checagem de sintaxe; git diff --check aprovado.
- Os arquivos de gatilhos, deadzones e pacotes de controle permanecem iguais à base ff69b8f.
- Sessões reais, RAM, Wi-Fi, decodificador e estabilidade na TL042 ainda precisam da validação física descrita acima.

## Correção da abertura preta — após o teste da build #24

Relato na TL042/Android 11: a ajuda inicial aparece, mas não há catálogo/login
nem resposta ao atalho do menu. A execução #24 corresponde a `76b990c`.
Não há logcat da TV para atribuir o bloqueio a um driver ou à rede.

Defeitos identificados e tratados:

- O splash esperava a criação da WebView, sem limite. Agora libera o primeiro
  desenho da Activity sem aguardar rede, Binder ou EGL.
- `onServiceConnected` fazia inicialização WebRTC síncrona na thread da UI.
  Inicialização e entrega da Surface passam por um único worker, sem bloquear
  os comandos da Activity. Eventos de Surface durante uma chamada em andamento
  são consolidados, em vez de criarem uma fila de recriações.
- O serviço construía EGL em `onCreate`, antes mesmo de concluir o bind. Agora
  essa construção ocorre na chamada de inicialização atendida pelo Binder.
- A inicialização tem prazo total de 45 s, incluindo bind, fábrica e Surface.
  A tela informa a etapa que não concluiu. Respostas de tentativas encerradas,
  falhas ou de uma Activity destruída não abrem o catálogo.
- O carregamento de uma página de catálogo/login tem prazo de 45 s. A WebView
  não é ocultada com GONE a cada navegação; commit visível pode revelar a página
  antes de todos os recursos terminarem. Erros de rede/HTTP do documento principal
  mostram recuperação; erro de subrecurso não substitui uma página funcional.
- A tela de carregamento oferece menu e saída pelo controle remoto. Em falha,
  oferece tentar novamente e abrir as configurações Android. Não apaga cookies.
- Voltar funciona mesmo antes da criação da WebView. Desvincular o serviço não
  espera uma chamada nativa na UI; a liberação nativa é serializada com a
  inicialização, em outra thread do serviço.

Um timeout não cancela uma chamada de driver/Binder travada. A opção de tentar
novamente não cria workers concorrentes para contornar isso. Se a chamada
continuar sem responder, a tela orienta forçar a parada nas configurações Android.
O limite da página não é um limite da fila do xCloud: não roda durante streaming.
Não foram alterados codec, bitrate, deadzones, protocolos ou o preset 720p/8 Mbps.

Validação local: 20 testes Java (incluindo 5 de transições de inicialização),
14 testes Node, Debug/Release compilados, lint sem erros (95 avisos).
Os testes verificam que timeout, retry e saída rejeitam respostas antigas;
não simulam o driver EGL/MediaCodec nem o processo real da WebView na TL042.

Próximo teste físico:

1. Atualizar com o APK Universal ARM da nova execução desta branch.
2. Abrir e fechar três vezes; confirmar catálogo/login e menu responsivo.
3. Se falhar, fotografar a mensagem e a etapa após 45 s. Tentar novamente uma
   vez; se a mensagem indicar chamada ainda presa, usar Configurações Android.
4. Pressionar Voltar durante carregamento; o aplicativo deve sair sem fechar
   por erro. Abrir novamente e verificar se uma tentativa antiga não reaparece.
5. Abrir com Wi-Fi desligado: deve haver erro recuperável do catálogo. Reativar
   a conexão e tentar novamente; o login salvo deve permanecer.
6. Só depois, aplicar o perfil TL042 e testar a entrada em três jogos. A build
   ainda precisa dessa validação para confirmar que resolveu o relato na TV.

## Falha de bind persistente — após o teste da build #28

O vídeo de 29/10 mostra `Conectando ao serviço de vídeo…`. O usuário confirmou
que termina em `Tempo esgotado: conexão com o serviço de vídeo`, em tentativas
repetidas. Isso situa a falha antes de `onServiceConnected`: fábrica WebRTC,
SDP, codec, bitrate e catálogo ainda não foram iniciados. O vídeo não contém
logcat nem prova a causa da ausência do callback.

A build #28 removeu o bloqueio da interface, mas deixou uma dependência anterior
ao bind: `StreamingService.onCreate` chamava NotificationManager/startForeground
sem tratamento de erro. O manifesto também declarava mediaProjection e
connectedDevice para um serviço que reproduz o vídeo recebido. Não há captura
de tela MediaProjection neste fluxo. Uma recusa/espera nesse caminho podia
impedir a conexão; ainda não foi comprovado que foi isso que ocorreu na TL042.

Alterações desta revisão:

- Serviço conecta como bound service; `onCreate`/`onBind` não fazem notificação,
  promoção foreground nem inicialização nativa. A Activity visível mantém a
  importância do serviço vinculado durante a abertura.
- Notificação e promoção são solicitadas somente com sessão ativa, usando o tipo
  mediaPlayback e a permissão já existente. As permissões de foreground
  mediaProjection/connectedDevice desnecessárias foram removidas.
- Trabalho de notificação roda em worker separado. RuntimeException na promoção
  é registrada sem encerrar o serviço bound. Encerrar a sessão remove a
  notificação; mudanças rápidas são consolidadas. Não se promete a mesma proteção
  contra encerramento em segundo plano se a promoção for recusada pelo Android.
- Em timeout de bind no Android 11+, consulta-se o histórico de saída somente do
  processo `:stream` e somente da tentativa atual. Motivo (falha Java/nativa,
  falta de memória, ANR etc.) e status aparecem na tela. Ausência de registro
  continua sendo causa desconhecida; um crash antigo não é atribuído à tentativa.
  A consulta roda fora da UI e não coleta stack traces, URLs ou credenciais.
- Logs distinguem solicitação de bind aceita, criação do serviço, retorno do
  Binder e recebimento do Binder pela Activity.

Validação: 29 testes Java e 14 Node aprovados; Debug/Release compilados; lint sem
 erros. Os novos testes cobrem notificação adiada, recusa de promoção, saída
 durante uma promoção lenta, consolidação de eventos e filtragem de histórico.
Ainda é necessária a instalação na TL042: abrir até o catálogo; se o bind falhar,
aguardar a linha de diagnóstico e fotografar a tela inteira antes de tentar
novamente. Não alterar perfil de vídeo para investigar esta etapa.

## Fluidez irregular — após a abertura funcionar na build #29

O usuário confirmou catálogo e jogos acessíveis. Relatou 30–45 FPS instáveis,
com EA FC 26 mais fluido durante gameplay e Gears, Forza 6 e Cold War injogáveis.
Os vídeos mostram preset 720/8 Mbps, vídeo/superfície 1280x720, H.264 42e01f e
OMX.MS.AVC.Decoder. Um trecho legível mostra aproximadamente 36 FPS renderizados,
5,9 Mbps, zero descartes do renderizador e zero pacotes perdidos acumulados.
Isso não identifica sozinho o gargalo e não prova capacidade sustentada de 60 FPS.

Erro de diagnóstico confirmado: `framesPerSecond` de inbound-rtp corresponde
à taxa de decodificação, mas era rotulado como FPS recebido. Portanto, o valor
34–36 mostrado anteriormente não permite atribuir a queda ao servidor/rede.

Correções e ferramentas de comparação:

- FPS recebidos e decodificados calculados separadamente pelas diferenças dos
  contadores no intervalo. Campos ausentes/reiniciados aparecem como n/d.
- Média de decodificação por quadro e permanência no jitter buffer no intervalo;
  descartes WebRTC e perdas de pacotes também por intervalo. RTT vem do par ICE
  selecionado pelo transporte do vídeo, com protocolo e relay quando disponíveis.
  RTT não é latência total de comando. Não se coletam endereços dos candidatos.
- Uma atualização completa do painel por amostra, sem a atualização parcial
  anterior que fazia o texto alternar e exigia mais uma chamada IPC/layout.
  Continua amostrando apenas com painel ligado, no ciclo existente do renderer.
- O mesmo Surface não é reenviado em cada surfaceChanged: resize dos buffers não
  exige destruir/recriar EGL, thread, drawer e sink. Eventos de criação/destruição
  identificam substituição real; um pedido explícito do serviço força reenvio
  para uma nova sessão. Retornos atrasados não satisfazem uma superfície nova.
- Ao liberar a saída, o sink pendente é limpo. Um callback de frame antigo mantém
  referência ao seu renderer original em vez de alimentar um renderer novo.
- Opções de 4 e 6 Mbps disponíveis para comparação manual em nova sessão.
  Preset/default continua 720p/8 Mbps. Não foi adicionado limite local de 30 FPS,
  nem forçado codec/driver, software, High, upscaling ou 60 FPS.

Validação: 40 testes Java e 14 Node aprovados, builds Debug/Release concluídos,
lint sem erros. Testes novos cobrem substituição/destruição de Surface durante
entrega assíncrona, reuso entre notificações de tamanho, nova sessão, diferenças
entre FPS recebidos/decodificados, contadores ausentes/reiniciados, decoder
parado, timestamps repetidos e RTT/relay sem expor endereços. Não simulam EGL,
MediaCodec ou o firmware da TL042.

Teste físico: conferir catálogo e entrada/saída sem regressão; iniciar um dos
jogos problemáticos em 720p/8 Mbps, aguardar duas amostras do painel e fotografar
FPS recebidos/decodificados, decodificação, buffer, perdas, RTT e renderer. Repetir
em 720p/6 Mbps com nova sessão e cena comparável. Registrar também como fica sem
o painel aberto. 4 Mbps é opcional e reduz qualidade. Só os resultados reais
podem confirmar ganho de fluidez e orientar uma mudança no decodificador.

## Controle ausente e congelamentos com/sem áudio — relato complementar

O usuário esclareceu que os testes eram anteriores às correções da build #30:
Gears sem controle, sessões variando entre 30–45 e 60 FPS, imagem congelada por
aproximadamente 15 s com áudio ainda tocando; sessões de 30–45 FPS às vezes sem
áudio. Isso não é um resultado de validação da build #30 e não comprova causa
única para áudio, vídeo e input.

Falhas de controle corrigidas nesta revisão:

- O navegador passa a deixar o anúncio/remoção de gamepad para o nativo. Depois
  de enviar authorizationRequest no canal control ordenado, a ponte pede o
  anúncio gamepadChanged e um estado atual do controle, mesmo parado. Não se
  interpreta isso como confirmação de autorização/execução pelo servidor.
- O anúncio exige canal control, transporte de input aberto e envio prévio da
  autorização. Estados abertos são reapresentados por snapshot nativo após
  registrar observadores, ao autorizar e no primeiro quadro. Preferência de
  transporte não pode selecionar um canal fechado.
- Atualizar a configuração ICE não reinicia o estado do controle. Fechar a
  sessão limpa rota/estado e callbacks de input são ignorados fora de sessão.
  O proxy bloqueia envios de um canal JavaScript de sessão antiga.
- O canal reliableinput deixa de ser bloqueado na saída: carrega sincronização
  do protocolo, enquanto input/unreliableinput de gameplay continuam nativos.
  Relatórios binários de entrada confiáveis chegam ao parser nativo de
  metadados/vibração, em vez de serem descartados.
- OPEN repetido não redispara a autorização do SDK. Contadores mostram eventos
  tratados e envios aceitos/recusados localmente; não são confirmação remota.

Diagnóstico de vídeo/áudio:

- Amostragem a cada 4 s em thread própria somente com painel habilitado. Deixa
  de depender da chegada de logs do renderizador, que pode parar numa falha EGL.
  Há no máximo uma consulta getStats pendente; resultados de epochs/sessões
  anteriores são descartados. Fechar o painel interrompe a amostragem.
- Mostra idade do último quadro entregue pelo decoder e idade do relatório EGL,
  evitando apresentar FPS antigos do renderer como medição atual num congelamento.
- Mostra bitrate de áudio, amostras recebidas por segundo e amostras ocultadas
  no intervalo; registra se a trilha chegou e callbacks de início/parada/erro
  da saída AudioTrack. Dados recebidos/saída iniciada não provam som audível.
- Ativar/desativar estatísticas usa uma chamada IPC assíncrona específica, sem
  depender do lock de criação/liberação da Surface.

Não foi aplicado reinício automático de decoder, limite de 30 FPS ou alteração
de codec para presumir a causa dos congelamentos. Não há promessa de corrigir
os congelamentos ou o áudio ausente sem a próxima evidência física.

Validação local: 47 testes Java e 19 Node, builds Debug/Release e lint sem erros.
Novos testes cobrem as ordens de autorização/abertura, canal fechado, nova
sessão, bloqueio de remoção pelo navegador, mensagens confiáveis, mensagens de
sessão antiga e áudio avançando enquanto a idade do último quadro cresce.
Esses testes não reproduzem o firmware, AudioTrack ou MediaCodec reais da TL042.

Próximo teste: instalar a nova build (inclui #30), manter inicialmente 720p/8 Mbps.
Abrir Gears, esperar 60 s sem movimentar e testar A/analógicos; fotografar o
painel se não funcionar. Em EA FC 26 ou outro jogo, manter o painel visível no
congelamento e registrar FPS recebidos/decodificados, idade do quadro/EGL,
áudio e saída AudioTrack. Comparar 6 Mbps em nova sessão somente depois dessa
amostra de referência. Registrar se som e menu continuam respondendo.
