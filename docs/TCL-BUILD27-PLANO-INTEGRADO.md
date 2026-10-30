# CloudX xCloud — análise da build TCL #27 e plano integrado

Data: 30/10/2026. Estado da auditoria original: proposta. Implementação autorizada posteriormente; consulte CONSOLIDACAO-XCLOUD-TV.md para o estado entregue e as validações pendentes.

## Base e escopo

- Build #27: https://github.com/Ricardex742/xbox-cloud-Solution-Android-tv/actions/runs/40773792423
- Branch confirmada no GitHub: `fix/tcl-input-high-negotiation`.
- Commit: `f10d6279791c0d4e3db5b79b3d12fb4e92a6def7`.
- Fontes: `testes.txt`, seis vídeos de 30/10, código desse commit e cópia local anteriormente revisada de Pgeniebox/cloudxsolution-android-tv.
- Os resultados são da TCL, não da Multilaser #31. Mudanças desta última são candidatas a integração seletiva, ainda sem validação física na TCL.
- Escopo do projeto: Xbox Cloud Gaming. Recursos de RomStation/PlayStation do outro repositório não entram no aplicativo.
- O relato escrito é evidência do teste do usuário; o conteúdo do arquivo não altera a autorização para modificar o projeto.

## Correspondência dos vídeos e evidência

Correspondência inferida pelo conteúdo e pela sequência dos nomes, pois o TXT enumera os testes sem citar os nomes dos arquivos.

| Teste | Vídeo | Evidência e limites |
|---|---|---|
| 1 — Gears sem resposta após esperar 60 s | WA0034, 5,61 s | Tela de título permanece; ~40 FPS, 1080p, Baseline 42e01f, decoder OMX.amlogic.avc.decoder.awesome2. Eventos sobem de ~2857 a 2889 e envios de ~3207 a 3291, sem recusas locais. A espera de 60 s é relatada no TXT, não gravada integralmente. A entrega/aceitação remota não é comprovada pelos contadores. |
| 2 — saída após Baseline | WA0036, 14,00 s | Tela escura; menu do aplicativo responde e permite recarregar catálogo; aparece carregamento. O catálogo utilizável não é mostrado até o fim. Isso não é prova de travamento completo do processo. |
| 3 — Forza, Baseline, baixa latência desligada | WA0040, 48,10 s | Transporte unreliableinput, ~30–35 FPS nas amostras principais, pausas/menu e retorno ao jogo, resolução 1920x1080. Ausência de áudio e menu sem comando são relatados no TXT; as imagens mostram as transições, mas não identificam qual evento físico as originou. |
| 4 — Gears, preferência High, baixa latência solicitada | WA0044, 26,29 s | Gameplay e movimento da câmera; ~38–52 FPS renderizados nas amostras, sem 60 constantes. High disponível/ofertado, porém Baseline negociado. Oferta [64001f,640c1f,42e01f], resposta [42e02a], RTCStats [42e01f]. Pacotes perdidos 54 e descartados 55 aparecem como contadores acumulados, não taxas atuais. Câmera sem problemas/drift e qualidade ruim são relatados pelo usuário. |
| 5 — saída após preferência High | WA0046, 12,35 s | Sair do Jogo → tela escura → carregamento (65–85%) → tela escura. Catálogo não aparece até o fim do clipe. |
| 6 — Forza, preferência High, baixa latência solicitada, Modern Input desligado | WA0049, 46,22 s | Painel confirma transporte input (legado); ~30–38 FPS renderizados, menu volta a aparecer; High solicitado mas Baseline negociado. Pacotes perdidos 0 nessa amostra, descartados 49 acumulados. |

As amostras foram extraídas ao longo de todos os clipes, com leitura dos painéis legíveis. Reflexos, movimento da câmera e compressão do celular limitam a avaliação de nitidez. A falta de som é tratada como relato do usuário, não como diagnóstico de AudioTrack obtido da gravação.

## Conclusões

1. **High ainda não foi negociado nos testes High.** A oferta muda de ordem, mas inclui Baseline. Preferência não obriga o servidor a escolher High. A diferença 42e01f/42e02a é de nível dentro da família Baseline e não demonstra uso de High. Não reescrever o identificador para simular um codec inexistente.
2. **O controle chega ao app no teste 1.** Falha posterior ao recebimento local é uma hipótese mais forte que simplesmente ausência de eventos físicos. Investigar registro, slot, autorização, serialização e aceitação do protocolo.
3. **Trocar Modern Input não elimina o menu inesperado.** O defeito se reproduz nos dois transportes. As proteções L2/R2/C/Z já existem nessa build; reaplicá-las sem capturar o evento real não constitui correção demonstrada.
4. **Saída para o catálogo falhou nas duas configurações.** A build verde e os testes anteriores não validaram esse comportamento no firmware real.
5. **Renderizador com Dropped: 0 não significa fluxo inteiro sem perdas.** Há descartes antes da renderização. A média reportada de decodificação é de aproximadamente 28–42 ms/quadro nas amostras legíveis; não é medição de uso de CPU nem prova isolada do limite físico do decoder.
6. **Não há diagnóstico fechado para baixo FPS ou áudio ausente.** Perdas acumuladas pequenas/zeradas não excluem jitter/retransmissão ou fila. Bitrate baixo em tela parada não prova falta de banda. Sem medir áudio recebido e AudioTrack, não há como separar transporte de reprodução.
7. **Os testes mudaram várias opções entre sessões.** Não atribuir a melhora do controle no segundo Gears ao High (não negociado) ou à baixa latência da TV. O indicador da tela diz solicitação, não confirmação do Jogo Mestre proprietário.

## Pontos encontrados no código #27

- `InputSessionState.canAnnounce()` exige canais abertos, mas não autorização enviada; `MainActivity` anuncia em eventos de canal e primeiro quadro e repete após autorização. A #31 contém uma barreira explícita para esse caso, candidata a portabilidade seletiva.
- `MainActivity.exitGameToCatalog()` chama `closeSession()` sincronamente antes de reconstruir o catálogo; a liberação do renderer/PeerConnection pode atrasar a UI. O carregamento observado nos vídeos mostra que essa chamada não explica necessariamente toda a falha.
- `onPageFinished()` e a visibilidade global de `isStreaming` controlam a exposição do catálogo; callbacks de visibilidade, ICE e tracks não compartilham uma identificação completa de geração/página. É necessário rejeitar eventos da sessão encerrada e identificar erro/carregamento do documento atual.
- `StreamingService.setRenderSurface()` destrói e recria EGL em cada chamada válida, mesmo sem mudança efetiva de Surface. Aproveitar o controle de reutilização da #30 e a proteção de sink antigo da #31.
- A telemetria depende dos logs de renderização e pode parar junto com o vídeo. Recepção por intervalo e `framesPerSecond` do decoder usam janelas diferentes; isso explica por que não devem ser comparados como contagens simultâneas exatas. Integrar amostragem independente e intervalos coerentes.
- Falta telemetria de áudio, idade das amostras e confirmação local das etapas de registro no painel #27.
- `setVideoCodecPreferences()` ordena capacidades, preservando Baseline. Não implementa seleção exclusiva de High.

## Plano de implementação proposto

### P0 — diagnóstico e recuperação da sessão

1. Preparar futura branch `feature/xcloud-tv-consolidation` a partir da TCL auditada; integrar por função as correções úteis da Multilaser. Manter presets por TV, testes de ambas e assinatura persistente. Não fazer merge indiscriminado.
2. Modelar estados catálogo/carregando/conectando/jogando/encerrando/erro; identificar geração da sessão e página em todos os callbacks. Executar fechamento nativo fora da UI e exibir progresso com prazo limitado e recuperação explícita.
3. Preservar login, impedir autoentrada na sessão antiga, mostrar erros de página/rede/renderizador WebView, tratar `onRenderProcessGone`, manter menu acessível. Considerar documento visualmente pronto (`onPageCommitVisible`) e verificação do catálogo, sem confundir progresso 100% com conteúdo pronto.
4. Integrar painel compacto e detalhado com FPS recebidos/decodificados/renderizados, idade da amostra e do último quadro, bitrate, perdas por intervalo, jitter/buffer, RTT, áudio RTP e estado AudioTrack. Amostrar em segundo plano apenas quando solicitado; relatório copiável sem tokens ou SDP com credenciais.

Aceitação: dez ciclos abrir/sair/reabrir em Gears e Forza, sem tela preta permanente, sem áudio residual, menu sempre acessível; falha de rede produz mensagem e nova tentativa em prazo definido, não espera infinita.

### P0 — controle consistente

5. Anunciar somente após envio da autorização e canais abertos; bootstrap do estado atual, limpeza por sessão, proteção contra callbacks antigos e validação do slot. Exibir separadamente anúncio enviado e confirmação remota somente se esta realmente existir no protocolo.
6. Acrescentar diagnóstico local temporário GameSir: origem/deviceId, keyCode/scanCode/action/repeatCount, eixos/gatilhos, estado lógico Menu e pacote serializado. Usar buffer limitado ativado pelo usuário, fora do caminho normal de desempenho.
7. Corrigir a camada identificada na reprodução: mapeamento Android, atalhos do app, foco WebView, transições/bitfields ou registro. Não suprimir Start verdadeiro nem atribuir automaticamente o problema a RT.
8. Testar moderno e legado, 60 s de inatividade, troca/reconexão de dispositivo, estado neutro, RT/LT 30 vezes e Start real. Preservar câmera com deadzone zero e mínimo esquerdo existente.

Aceitação: Gears responde após espera sem mexer no controle, Forza não abre menu com gatilhos, Start real funciona e não há botões presos após menu/saída/reconexão.

### P1 — vídeo e áudio

9. Evitar recriação redundante de Surface/EGL; vincular sink à sessão/renderer correto. Investigar filas, retomada e áudio usando as novas medidas antes de escolher recuperação automática.
10. Comparar mesma cena, mesma resolução, mesmo transporte e uma opção por vez: Baseline/baixa latência desligada; depois somente baixa latência; depois somente preferência High. Repetir sessões, porque a instabilidade varia entre inicializações.
11. Opções de perfil com resultado explícito: Automático/Baseline/Preferir High. Estudar modo High exclusivo experimental somente quando capacidades e parâmetros completos forem compatíveis; preservar áudio/RTX, falhar claramente se o serviço recusar e oferecer nova sessão Baseline. Não prometer High nem trocar apenas texto do SDP.
12. Comparar 1080p com 720p como diagnóstico, e bitrate moderado com maior mantendo o restante constante. Presets TCL qualidade/equilíbrio e TL042 compatibilidade, sem importar o padrão de 720p da Multilaser para a TCL.
13. Tratar áudio conforme evidência: trilha não chegou, dados não avançam, AudioTrack não iniciou/parou ou rota de saída incorreta. Microfone desligado por padrão nos testes para isolar reprodução; não substituir AudioTrack ou taxa de áudio sem causa demonstrada.

Aceitação: sessões repetidas de pelo menos 20 min nos jogos relatados, som presente e ausência de congelamentos longos; registrar desempenho medido. Não anunciar 60 FPS garantidos nem converter um stream de 30 em 60 por simples opção.

### P2 — funcionalidades do projeto xCloud e qualidade visual

14. Perfis salvos por TV e, opcionalmente, por jogo, com valores realmente aplicados e aviso quando exigirem nova sessão; restaurar perfil estável em um passo.
15. Diagnóstico de mapeamento por controle, teste de botões/eixos, sensibilidade e vibração. Aproveitar `GamepadDiagnostics` do outro repositório após revisão; exemplos como `StreamingInputDispatcher`/`WebRtcInputWiring` não são tratados como subsistemas prontos.
16. Revisar as opções já existentes de microfone, vibração e botão Xbox/Nexus, expondo estado/compatibilidade corretos. Não apresentá-las como funções inéditas já ausentes.
17. Upscaling permanece no plano: primeiro preservar resolução nativa e aspecto; depois experimentar escala para a resolução de saída e nitidez leve no renderizador. Usar passagem GPU única quando viável, sem readback, comparar tempo por quadro e latência, opção desligada/padrão disponível. Evitar forçar buffers 4K antes de medir custo. Não é recuperação de detalhe original nem acesso garantido ao processador proprietário TCL.
18. Baixa latência Android opcional, suporte/solicitação separados de confirmação. Jogo Mestre TCL só pode ser ativado de forma confiável se houver interface do fabricante acessível ao app; não prometer forçá-lo em aplicativo interno.
19. Worker/polling alternativo somente como experimento com medição, uma única fonte de input e sem filas crescentes. Não introduzir pipeline concorrente ao nativo por copiar trechos do outro projeto.
20. Coop local permanece investigação de capacidade real do xCloud e dos jogos; índices de controle e estruturas multigamepad não bastam para confirmar suporte operacional. Não anunciar como recurso implementado.

Aceitação: novas funções não degradam input/áudio/saída; ganho de qualidade comparado no mesmo local com overlay desligado, mantendo dados de desempenho obtidos em teste separado.

## Entrega e limites

- Entregas futuras pequenas: A sessão/diagnóstico; B controle; C vídeo/áudio/perfis; D upscaling e extras. Cada entrega com testes de regressão, APK identificável por commit e roteiro curto na TV.
- Testes de host: ordenação de autorização/canais, neutralização/bitfields, recuperação e descarte de callbacks antigos, métricas por intervalo, preservação SDP áudio/RTX e falha explícita na seleção incompatível. Testes Android/WebView para saída e foco; CI não substitui o firmware TCL.
- Não se considera a #27 validada para uso estável. O resultado positivo relatado de câmera/drift em uma sessão será preservado como caso de regressão, sem generalizar para todas as sessões.
- Registro da auditoria anterior à autorização. A implementação autorizada está descrita em CONSOLIDACAO-XCLOUD-TV.md.
