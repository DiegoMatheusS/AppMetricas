# Medição de cercas e detalhes distantes

Os controles ficam no painel inferior, com ícones e nomes curtos. Na câmera, **Auto** encontra as arestas sem exigir toque e usa a superfície reconhecida. Arraste as pontas para ajustar. O toque simples fica inativo até ativar **Objeto** ou **Pontos**, que permitem selecionar um alvo ou marcar os extremos. Se o suporte ARCore estiver instalado, a régua e a área entram em Auto. A foto exige ativar **Escala**, **Objeto** ou **Pontos** antes de marcar. Esses modos não pedem o tamanho de uma referência, mas exigem suporte e rastreamento ARCore válidos. **Foto** é a opção calibrada descrita abaixo. Uma fotografia isolada não fornece escala física automaticamente.

1. Enquadre a cerca de frente, inteira se possível, com uma referência conhecida no mesmo plano (por exemplo, a altura verificada de um mourão).
2. Use o zoom disponível na câmera e toque em **Foto**. A fotografia permanece fixa para conferir a seleção.
3. Marque os dois extremos da referência amarela e informe seu tamanho real. A escala depende dessa informação.
4. Em **Objeto**, toque na cerca. A seleção recebe um brilho no contorno e o entorno escurece. Os demais contornos ficam ocultos por padrão; **Arestas** permite conferi-los. Sem escala, o objeto informa a pendência e oferece **Auto**, evitando uma borda sem explicação. A régua mede a maior aresta do contorno selecionado; a área mede sua projeção na fotografia. Tocar em outro objeto substitui a medição anterior.
5. Se o scanner escolher apenas uma parte, tente **Ampliar**. Se não houver um contorno completo, use **Pontos** e marque os extremos pretendidos. Não há agrupamento semântico de todos os fios e mourões.
6. Amplie a foto até 8×, mova com dois dedos e escolha **Ponto → Fino** para detalhes pequenos. Arraste uma ponta para visualizar a lupa; o botão **Ponto** altera o tamanho do marcador. O zoom não muda a escala já calibrada.
7. Confira as pontas e salve. Resultados são estimativas; objetos inclinados em relação à câmera e planos diferentes da referência distorcem a medida.

## Comportamento técnico

- A detecção local usa OpenCV: contraste adaptativo, múltiplos limiares, contornos e segmentos Hough. A análise da fotografia ampliada reprocessa o recorte visível usando os pixels originais. Não cria resolução ou profundidade que a câmera não capturou.
- Uma transformação única converte coordenadas entre a fotografia, os gestos e as marcações. A escala usa pixels originais e a referência física, nunca pixels de tela.
- O traçado detalhado controla a seleção e a área automática. Arrastar uma ponta troca a área automática pelo polígono editado pelo usuário.
- Pontos maiores alteram somente a apresentação e a tolerância de seleção. A preferência é compartilhada com a medição em AR.
- No modo automático em AR, o toque seleciona um contorno e reinicia a confirmação. Só há medida física após reconhecer a superfície/profundidade e confirmar estabilidade. O limite de leitura AR não é ampliado artificialmente.
- O efeito de seleção faz uma varredura luminosa por 950 ms e mantém o contorno destacado. Não utiliza reconhecimento ou serviços da Apple.

O comprimento da cerca e a distância entre a câmera e a cerca são grandezas diferentes. A fotografia calibrada estima o primeiro. O segundo requer uma leitura de profundidade ou outro método de levantamento.
