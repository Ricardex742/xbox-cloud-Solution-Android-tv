# Nitidez e desempenho na TCL 55P755

Data: 27/10/2026. Base: 0d45bac. Relato do usuário: controles corrigidos nos testes até agora, mas imagem menos nítida mesmo com a câmera parada. A resolução real, o perfil negociado e a taxa efetiva da sessão relatada ainda não foram medidos.

## Brainstorm e decisão

| Alternativa | Benefício esperado | Custo ou limite | Decisão |
|---|---|---|---|
| Superfície com o tamanho dos quadros recebidos | Evita reduzir o vídeo à resolução da interface da TV antes de ampliá-lo | Mudança de tamanho pode recriar a saída EGL uma vez | Implementar primeiro |
| Confirmar resolução e bitrate reais | Diferencia resolução baixa de compressão e escala | Estatísticas nem sempre expõem todos os campos | Mostrar no painel existente |
| Teto de 20 Mbps com início moderado | Recupera uma sugestão de taxa inicial sem impor um piso à rede | O servidor pode ignorar atributos x-google; teto não é taxa garantida | Início de metade do teto, até 10 Mbps; sem mínimo |
| H.264 High opcional por hardware | Pode preservar mais detalhes com a mesma taxa | Histórico de congelamento na TV; suporte anunciado não prova estabilidade | Opt-in experimental; Baseline continua padrão |
| 1440p, bitrate acima de 20 Mbps ou filtros de nitidez | Podem aumentar a definição aparente | Maior custo de decodificação/GPU/rede; filtros não restauram detalhes perdidos | Não incluir nesta correção |

O uso de H.264 High não prova, sozinho, que o servidor envie B-frames ou que haja uma causa específica de travamento. Baseline também não garante latência zero. A melhoria perceptiva exige teste na TV.

## Problemas confirmados no código

1. MainActivity usava SurfaceViewRenderer para hospedar a Surface, mas quem desenhava os quadros era EglRenderer no processo :stream. A instância da interface não recebia quadros nem controlava a escala do renderizador ativo. O tamanho do buffer ficava vinculado ao layout, podendo reduzir um stream 1080p à resolução da interface da TV. Isso é uma possibilidade concreta do código; ainda não foi demonstrado que ocorreu na sessão relatada.
2. A oferta nativa anterior incluía somente b=AS. A retirada dos parâmetros de início de taxa é confirmada, mas não sabemos se o servidor os respeita. Restaurar sugestões não obriga o servidor a enviar 20 Mbps.
3. A preferência fixa por Baseline não oferecia uma comparação com High por hardware. O ranking antigo também não reconhecia variantes Constrained High (640c) e alguns aliases Constrained Baseline.
4. O painel de desempenho mostrava métricas do renderizador, sem resolução recebida, bitrate por intervalo e perfil efetivo.

## Implementação

- SurfaceView passa a hospedar diretamente a saída do serviço. O serviço comunica mudanças reais de dimensões; a interface aplica setFixedSize apenas se o buffer divergir. A checagem ocorre por quadro, mas IPC/realocação ocorrem somente ao mudar de tamanho, não 60 vezes por segundo. Não foi adicionado filtro de imagem ou cópia de pixels.
- Opções Estável (padrão) e Mais detalhes (experimental). A preferência atravessa o Binder junto com o pedido da oferta, sem ler SharedPreferences no processo errado. High só é promovido se o perfil estiver nas capacidades reais do receptor e no anúncio do decoder de hardware. Não há troca automática de perfil durante a sessão nem garantia de fallback em um congelamento.
- SDP alterado antes de setLocalDescription: b=AS/b=TIAS coerentes, sugestões de início/máximo apenas para H.264, sem piso obrigatório. Áudio, RTX, canal de dados, perfis e níveis reais são preservados. Seções de vídeo rejeitadas não são alteradas.
- Estatísticas consultadas apenas quando o painel está visível e chega a telemetria já existente do renderer. Bitrate calculado por diferença de bytes e tempo. Resolução, codec/profile, decoder, FPS recebido, perdas acumuladas de pacotes e superfície são exibidos. Campos ausentes aparecem como n/d; primeira taxa aparece como medindo. Não há timer adicional nem inspeção de pixels.
- Controles, deadzones, retransmissões de neutro, transporte de comandos, região, padrão 1080HQ e workflow de assinatura permanecem com o comportamento anterior. Preferências de resolução/bitrate já salvas são respeitadas.

## Validação na TV

1. Selecionar Brasil, 1080HQ, 20 Mbps e Estável. Encerrar a sessão e abrir o jogo novamente: salvar a configuração não renegocia a sessão em andamento.
2. Abrir o painel de desempenho. Após duas atualizações, registrar Vídeo recebido, Superfície, Codec, Decodificador, Mbps, FPS e perdas. Para a comparação em 1080p, esperar 1920x1080 tanto no vídeo quanto na superfície. Se o vídeo vier em 1280x720, a origem está abaixo de 1080p mesmo com 1080HQ selecionado; o buffer não consegue inventar os detalhes ausentes.
3. Comparar o mesmo local parado: placas, texto de HUD, vegetação e objetos distantes. Desativar o painel para julgar a imagem. Manter configurações da TV iguais.
4. Jogar 20 minutos com movimentos rápidos de câmera. Verificar congelamentos, áudio sem vídeo, FPS renderizado, perdas de quadros e percepção de atraso contra a versão anterior. Rede estável e servidor igual melhoram a comparação. VBR pode usar menos Mbps numa cena parada sem ser defeito.
5. Somente depois, experimentar Mais detalhes, mantendo resolução/bitrate iguais e abrindo nova sessão. O perfil mostrado deve confirmar se High foi realmente negociado; a seleção do menu é uma preferência. Se houver travamentos ou atraso perceptível, voltar a Estável e abrir nova sessão.
6. Se surgirem perdas/congestionamento, comparar 18 ou 15 Mbps em nova sessão. Não subir bitrate e resolução simultaneamente.
7. Conferir RT/LT, retorno do analógico esquerdo e câmera direita rapidamente como regressão, embora esses arquivos não tenham sido modificados.

O teste local de build/SDP não simula MediaCodec, composição de Surface da TCL, servidor xCloud, imagem subjetiva ou latência física. A melhoria na TV permanece pendente de confirmação.

## Resultado local

- 14 testes unitários aprovados, zero falhas (incluindo os 5 testes de regressão de controle).
- testDebugUnitTest, lintDebug, assembleDebug e assembleRelease aprovados com JDK17/Gradle8.13.
- Lint: zero erros e 94 avisos; não foi adicionada supressão ou baseline.
- Sintaxe do JavaScript e git diff --check aprovados. index.js, arquivos de controle e workflow sem mudanças.
- Interface física, negociação real de SDP com xCloud, consumo de GPU, qualidade e latência na TCL ainda precisam do roteiro acima.
