# AppMetricas — Medidor para Android

Aplicativo Android nativo em **Kotlin e Jetpack Compose**, para medições estimadas por câmera, cálculo de reservatórios, modelagem arquitetônica assistida e captura guiada e reconstrução parcial de superfícies 3D.

**Estado:** em desenvolvimento, ainda não publicado na Play Store. Android 11 ou superior (API 30+).

## Funcionalidades

### Medições e câmera
- Câmera livre na parte superior. Todos os controles ficam em um painel inferior opaco, com botões uniformes, ícones, nomes curtos e descrições acessíveis completas. A prévia CameraX usa TextureView e recorte explícito para preservar a leitura do texto no rodapé.
- Captura de fotografia com CameraX ou importação da galeria.
- Distância por marcação manual de pontos, com escala calibrada por referência física no **mesmo plano**. Permite calibração por moeda brasileira ou distância conhecida (mm, cm, m).
- **Contorno experimental por toque na fotografia:** seleciona uma região com cor semelhante à do ponto tocado, destaca o contorno e estima largura e altura pela caixa delimitadora. Há três níveis de tolerância para ajustar a seleção.
- **Régua e Área:** o botão **Marcar pontos na foto** congela a imagem, solicita uma referência real e permite marcar extremos ou cantos. As medidas aparecem nas linhas e as pontas podem ser arrastadas. Quando o suporte ARCore já está instalado, a régua e a área abrem em **Auto**, calculando sem tocar e sem informar um tamanho conhecido. Arrastar uma ponta corrige a medição ancorada. O toque simples fica inativo até ativar **Objeto** (selecionar) ou **Pontos** (marcar extremos). A compatibilidade é verificada; **Auto** permite ativar o suporte quando necessário. **Foto** é a alternativa com referência física explícita. Os contornos ao vivo ficam como opção nos ajustes.
- **Seleção de objetos e arestas:** o toque simples não muda a medição. **Auto** encontra contornos ao reconhecer a superfície; **Objeto** ativa a seleção explícita por toque, e **Pontos** ativa a marcação manual. Na foto, essas ações também exigem ativar um botão; a referência é marcada somente depois de escolher **Escala**. Após calibrar uma referência real, o modo **Objeto** procura contornos fechados e segmentos abertos, destaca a seleção com uma varredura luminosa e escurece o entorno. Tocar em outro objeto recalcula somente suas arestas: a régua usa a maior aresta e a área usa o traçado do contorno. **Ampliar seleção** e **Reduzir** percorrem contornos sobrepostos; **Pontos** permite definir os extremos manualmente. No modo **Objeto** em AR, o toque restringe a busca ao alvo escolhido, mantendo as verificações reais de superfície e estabilidade.
- **Detalhes distantes:** zoom da câmera conforme o suporte do aparelho, fotografia com zoom de 1× a 8× e deslocamento por gesto. O scanner reanalisa o recorte ampliado da imagem original. Pontos **Fino**, **Médio** e **Grande** têm preferência persistente; arrastar uma ponta abre uma lupa. Foto, contornos e toques usam a mesma transformação. O tamanho visual do ponto e o zoom não alteram a escala física. Consulte [MEDICAO_DISTANTE.md](docs/MEDICAO_DISTANTE.md).
- **Contorno ao vivo na câmera (experimental):** busca automática com confirmação em quadros consecutivos. Você também pode tocar no objeto ou **delimitar a área** por dois cantos. A seleção por área restringe a busca para reduzir interferência de fundos semelhantes; permite redefinir a região e ajustar a tolerância de cor. O app informa **estabilidade visual heurística** e avisa se o contorno toca as bordas, está fragmentado ou oscila entre quadros. CameraX processa offline até cerca de quatro quadros por segundo e compartilha recorte/rotação com a prévia. Mostra largura e altura apenas como porcentagens do quadro, nunca centímetros sem calibração.
- Histórico local dos resultados, exclusão individual com confirmação e exportação de relatório PDF A4.
- Calculadora offline de caixas: volume, área de piso e área superficial.

**Limitações:** o contorno ao vivo depende do contraste de cores; não é reconhecimento semântico, rastreamento confiável de objetos nem medição de profundidade. Movimentos rápidos, reflexos, perspectiva e fundos semelhantes podem causar perda da seleção. Mesmo com estabilidade visual aparentemente boa, o contorno pode selecionar o objeto errado, não identificar partes desconectadas ou cortar as bordas de uma área delimitada. Apesar do corte compartilhado, alinhamento e rotação precisam ser validados em celulares físicos. As proporções do quadro **não são centímetros**. Para medir em unidades físicas, capture uma foto e marque uma referência conhecida no mesmo plano e distância do objeto.

A seleção por arestas também é aproximada: não reconhece semanticamente uma cerca inteira, não completa partes ocultas e pode selecionar apenas uma célula da malha. Use a ampliação da seleção ou marque os extremos. As arestas dos demais objetos ficam ocultas por padrão; somente a seleção recebe destaque. Sem escala, a foto informa como calibrar e oferece acesso a Auto, sem exibir centímetros fictícios. O brilho é um efeito visual local; não usa serviços ou modelos da Apple. Uma referência no mesmo plano é necessária na fotografia, preferencialmente de frente. A distância da câmera até a cerca exige profundidade real; o comprimento medido na foto não fornece essa distância.

### Tanques e produtos armazenados
- Tanques cilíndricos verticais, horizontais e esféricos, modelados como sólidos geométricos ideais.
- Calcula capacidade total, volume ocupado e volume livre em metros cúbicos e litros.
- **Tanques em AR:** selecione o formato e marque dois extremos para cada dimensão solicitada na mesma sessão. A opção automática procura as seções centrais do contorno para cada dimensão. A câmera pode se deslocar entre as medidas. A ferramenta requer profundidade ARCore e preenche a calculadora com as medidas confirmadas. Dimensões externas produzem uma estimativa geométrica.
- Nível do líquido ou produto medido a partir do fundo, com entrada em **metros, centímetros ou percentual da altura interna**.
- Massa estimada opcional, mediante densidade aparente informada em kg/m³.

**Limitação:** o percentual da altura não é igual ao percentual do volume em tanques horizontais ou esféricos. Os cálculos não representam arqueação certificada nem incorporam deformações, fundos irregulares, tubulações, volume morto ou variação de densidade.

### Modo Arquiteto
- Importação de imagens e páginas de PDF como referência visual para traçar plantas.
- Importação de **DXF ASCII** com entidades `LINE` e `LWPOLYLINE` de segmentos retos. Permite visualizar as camadas (layers) e escolher **uma camada** para transformá-la em paredes editáveis; também é possível usar o desenho apenas como imagem de referência.
- Para converter linhas DXF em paredes, exige escolher explicitamente a unidade física: mm, cm, m, polegadas ou pés. Há limite de 300 paredes por importação. Polilinhas curvas com `bulge` não são suportadas.
- Calibração da escala, reconhecimento assistido de paredes horizontais, verticais e inclinadas com compensação de iluminação; portas e janelas nas paredes alinhadas aos eixos. Revisão das sugestões sobre a imagem e edição manual das marcações. Até seis pavimentos por projeto.
- Zoom de precisão de até 6×, deslocamento da planta com dois dedos, correção manual de perspectiva para folhas planas e alinhamento entre andares por um ou dois pontos de referência.
- Maquete geométrica **3D interativa**, com rotação e zoom. Exportação de geometria no formato **OBJ**, sem texturas ou propriedades BIM.
- Até 20 projetos locais, com criação, duplicação, renomeação, seleção e exclusão confirmada.
- Relatório PDF A4 do projeto e backup/restauração local em ZIP, incluindo plantas e histórico de medições.

**Limitações:** o reconhecimento de paredes e aberturas usa contraste, linhas retas e intervalos na planta calibrada. As sugestões precisam ser revisadas. Converter DXF depende da seleção correta da camada e das unidades; outras linhas da camada escolhida também poderão virar paredes. Não há suporte nativo para DWG, IFC ou RVT. A maquete é esquemática, não um projeto executivo nem um modelo BIM certificado. O backup é manual, não criptografado e não possui sincronização em nuvem.

### Realidade aumentada (ARCore)
- **AR na mesa:** posiciona a maquete em uma superfície horizontal detectada.
- **AR sobre papel:** tenta reconhecer a planta impressa e ancorar nela uma maquete em escala de apresentação, a partir da largura física informada da folha.
- Exibe informações sobre rastreamento e avisa quando a imagem de referência tem poucos detalhes.

**Limitação:** é uma função experimental, disponível apenas em aparelhos compatíveis com ARCore. A precisão da âncora, o reconhecimento da impressão e a estabilidade ainda precisam de validação em aparelho físico. A visualização não representa aferição de obra.

### Scanner 3D — fotografias e reconstrução
- Captura guiada de **20 posições** (12 fotografias horizontais e 8 elevadas) por objeto, com câmera ou galeria.
- Até oito sessões locais; permite trocar fotografias, acompanhar o progresso e renomear ou excluir sessões.
- Exportação e importação de **cada sessão** em ZIP independente, com validação de integridade SHA-256.
- Análise offline e aproximada de exposição, detalhes/contraste e possíveis fotografias muito semelhantes.
- Comparação de pontos visuais entre fotos por descritores binários e RANSAC 2D; verificação experimental de coerência epipolar entre imagens.

- Reconstrução offline por correspondências ORB, com uma segunda tentativa usando realce de contraste quando a extração original é insuficiente, matriz essencial, recuperação de pose, triangulação e alinhamento progressivo das fotografias. Refina cada ponto contra todas as câmeras que o observam e remove observações inconsistentes antes de criar a superfície. A superfície parcial pode ser girada na prévia e exportada como OBJ.
- O campo de visão horizontal informado configura a câmera estimada. O modelo tem escala proporcional, depende da sobreposição e textura do objeto e pode conter lacunas. As dimensões físicas exigem calibração externa. Fotografias insuficientes ou inconsistentes geram uma orientação para refazer a captura.
- O modelo salvo acompanha as fotografias que o originaram. Alterar as fotos exige reconstruí-lo. O ZIP preserva as fotos para reconstrução posterior.

### Contagem de peças
- Mostra objetos detectados sem exigir uma seleção inicial. Tocar em uma peça filtra por aparência/cor. Compensa gradientes suaves de iluminação estimando o fundo pelas bordas. Duas peças intactas de aparência e tamanho compatíveis podem fornecer a escala para separar uma região de peças encostadas da mesma cor; sem evidência suficiente, mantém a região única para revisão.
- Após confirmar a quantidade, a posição e o tamanho das regiões em três quadros recentes, fixa a imagem e a contagem; **Nova leitura** inicia outro escaneamento. Uma cena vazia informa zero e continua buscando peças. Pausas, imagens ilegíveis e mudanças de resolução reiniciam a confirmação.
- **Conferir foto** permite adicionar e remover marcações antes de salvar. A identificação continua aproximada e depende da imagem; não recupera peças ocultas.

### Versão Pro
Modelo comercial: versão básica gratuita e um único **Pro Completo**, com preço previsto de **R$ 129,90**, em pagamento único. A integração de compra, confirmação de entrega, validação de recibo assinado, restauração e controle de acesso está implementada com Google Play Billing. **A venda depende da configuração real do produto, chave pública e publicação na Play Console.** O preço mostrado vem da loja; não há assinatura. Sem configuração de venda, as ferramentas permanecem liberadas para testes, sem simular compra. Consulte [PLAY_BILLING.md](docs/PLAY_BILLING.md).

## Tecnologias e compilação

- Android Gradle Plugin **9.2.1**; Gradle **9.4.1**; Kotlin embutido no AGP; Jetpack Compose BOM **2026.09.00**.
- CameraX **1.6.2**, ARCore, OpenCV **4.13.0**, OpenGL ES 2.0 e Java **17**.
- `compileSdk 37`, `targetSdk 36`, `minSdk 30`.

No Android Studio, abra a pasta do projeto, instale o Android SDK 37 e sincronize o Gradle.

Compilação e testes pela linha de comando:

```bash
gradle :app:assembleDebug :app:testDebugUnitTest
```

O GitHub Actions executa a compilação e os testes automatizados a cada atualização pertinente.

## Armazenamento, segurança e validação

- Os projetos, fotos e resultados ficam no aparelho. Não há upload obrigatório, login ou serviço pago de processamento.
- Os arquivos ZIP de backup **não são criptografados** e devem ser armazenados em local seguro.
- Projetos, histórico e sessões do Scanner usam documentos versionados em SQLite com migração automática das preferências anteriores. Projetos e histórico são restaurados em uma única transação; uma falha preserva os dois conjuntos anteriores. Fotografias usam gravação atômica. A migração não apaga os dados originais.
- Testes automatizados **não substituem testes em celular real**: ainda é necessário validar câmera, importação PDF/DXF, contornos, níveis de tanque, AR, ZIP, PDF, OBJ, desempenho e consumo de memória.
- As medições do aplicativo são estimativas e **não substituem laudos técnicos, certificações, medições fiscais ou levantamentos profissionais**.

## Próximas etapas

1. Evoluir o contorno aproximado no vídeo ao vivo para rastreamento mais estável, com calibração dinâmica ou profundidade confiável antes de mostrar medidas físicas.
2. Melhorias na importação e identificação de paredes e aberturas de plantas, com suporte progressivo a outros formatos arquitetônicos.
3. Validar a reconstrução do Scanner 3D com fotografias reais e melhorar a calibração e a densidade das superfícies.
4. Validar migração e restauração com projetos reais de aparelhos usados anteriormente.
5. Configurar o Pro Completo na Play Console e validar compras pela faixa interna da loja; conferir a contagem assistida com peças reais rotacionadas ou encostadas.
