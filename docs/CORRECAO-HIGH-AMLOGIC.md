# Seleção H.264 High em decodificadores Amlogic

Base: ff69b8f. Correção exclusiva da branch fix/tv-video-quality.

## Evidência e causa

Na captura enviada pelo usuário, o app recebeu 1920x1080 a aproximadamente 30 FPS e 10,1 Mbps, com perfil 42e01f e decoder OMX.amlogic.avc.decoder.awesome2. A superfície também era 1920x1080; naquele intervalo, não houve quadros descartados nem perdas de pacotes. Isso não demonstra uma limitação a 30 FPS do decoder nem que o servidor tenha aceitado 20 Mbps como taxa constante.

A inspeção do bytecode da dependência io.getstream:stream-webrtc-android:1.3.10 confirmou que MediaCodecVideoDecoderFactory.isH264HighProfileSupported usa prefixos OMX.qcom., OMX.Marvell. e OMX.Exynos. para anunciar High. Amlogic fica de fora. O app usava esse anúncio tanto para as capacidades nativas quanto para a condição de prioridade; mudar a ordem dos codecs não podia acrescentar um High ausente.

H.264 Baseline e H.264 High continuam sendo video/H264. A distinção está no profile-level-id, por exemplo 42e01f contra 640c1f. O nome MIME ou o nome do decoder pode permanecer igual mesmo quando o perfil muda.

## Correção

- CapabilityVideoDecoderFactory consulta a lista MediaCodec do Android. Exige decoder de hardware, AVC High/Constrained High com suporte a pelo menos nível 3.1, formato de cor compatível com o adaptador WebRTC, e ausência de exigência de reprodução segura/tunelada.
- O suporte High agora é anunciado pela fábrica nativa, usando os parâmetros Constrained High padrão da biblioteca, somente quando essas verificações passam. Não é uma substituição de texto de Baseline por High no SDP.
- O decoder High é criado pelo mesmo nome de componente que passou na inspeção. Assim, não se consulta um componente High para depois usar outro decoder AVC sem esse suporte.
- Os perfis High herdados da lista de fabricantes são substituídos pela capacidade verificada. Baseline e outros codecs continuam no caminho padrão. Não há fallback silencioso de High para decodificação em software.
- A comparação de prioridade deixa de depender da igualdade completa de profile-level-id entre duas enumerações. Variações legítimas de nível no mesmo perfil High não bloqueiam a prioridade.
- O painel separa o perfil solicitado para a sessão, a disponibilidade local de High, sua presença na oferta nativa e o perfil efetivamente recebido. O perfil mostrado continua vindo das estatísticas reais, não do botão escolhido.
- Os nomes do menu passam a ser Preferir Baseline (estável) e Preferir High (experimental). O servidor ainda pode negociar outro perfil da oferta. Não se promete que ordenar uma lista obrigue o servidor a selecionar High.

A implementação usa classes internas do pacote org.webrtc para manter o adaptador MediaCodec existente. A dependência permanece fixada em 1.3.10; uma atualização da biblioteca exige rever essa integração.

## Como validar

1. Instalar o APK desta atualização da branch fix/tv-video-quality.
2. Selecionar Preferir High, aplicar, encerrar a sessão e abrir o jogo novamente. Alterar a preferência durante uma sessão não troca o decoder em uso.
3. Conferir no painel: Perfil solicitado nesta sessão: High; High por hardware: disponível; High ofertado: sim; Perfil negociado: H.264 High. O código de perfil deverá começar por 64 quando High tiver sido negociado. O nome video/H264 pode permanecer igual.
4. Se High estiver indisponível, o Android não confirmou um decoder utilizável com os critérios acima. Se estiver disponível mas não ofertado, a limitação está na oferta nativa. Se estiver ofertado e o perfil recebido for Baseline, a sessão negociou um fallback. Esses casos não são apresentados como uma troca bem-sucedida.
5. Comparar com Baseline no mesmo jogo, nova sessão, mesma resolução e bitrate. Registrar nitidez, FPS recebidos/renderizados, decoder e congelamentos por 20 minutos. Se High travar, voltar a Baseline e abrir outra sessão.

## Validação automatizada e limites

Os testes simulam uma fábrica padrão sem High (caso Amlogic), uma fábrica High verificada, seleção do decoder correto, recusa de High não verificado, perfis/níveis insuficientes, detecção na oferta e distinção de estados no painel. Os testes anteriores de input e SDP permanecem.

A inspeção da biblioteca confirma o defeito da lista de fabricantes. A capacidade efetiva do MediaCodec desta TV e a aceitação de High pelo servidor ainda dependem do novo teste no dispositivo. Nenhum teste em host comprova estabilidade física de High na TCL.

Resultado local: 20 testes aprovados; builds debug/release e lint concluídos, zero erros e 94 avisos de lint. Checagem de sintaxe do JavaScript e git diff --check aprovadas. Arquivos de controle, protocolo JS e workflow permaneceram iguais à base desta branch.
