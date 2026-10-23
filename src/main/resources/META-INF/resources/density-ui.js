/* Integra a correção de densidade sem alterar a função desenhar() original. */
(function () {
  const desenharOriginal = desenhar;

  desenhar = function () {
    desenharOriginal();

    const rows = lote?.etiquetas || [];
    const trs = $('corpo').querySelectorAll('tr');

    rows.forEach((r, index) => {
      const needsDensityFix = r.avisos?.some(a => a.includes('Pico de densidade 20 linhas'));
      if (!needsDensityFix) return;

      const action = trs[index]?.lastElementChild;
      if (!action || action.querySelector('.density-action')) return;

      const button = document.createElement('button');
      button.className = 'secondary row-action density-action';
      button.style.marginTop = '6px';
      button.textContent = 'Corrigir densidade';
      button.title = 'Atenua grandes blocos pretos no gráfico da etiqueta';
      button.addEventListener('click', async () => {
        if (!confirm(`Aplicar correção de densidade à etiqueta ${r.numero}? A correção altera apenas blocos pretos horizontais extensos. Faça uma impressão de teste e confira códigos de barras e rota.`)) return;
        button.disabled = true;
        button.textContent = 'Corrigindo…';
        try {
          const corrected = await request(`/api/lotes/${lote.id}/etiquetas/${r.numero}/corrigir-densidade`, { method: 'POST' });
          Object.assign(r, corrected);
          desenhar();
          aviso(`Densidade da etiqueta ${r.numero} corrigida. Confira a legibilidade e faça uma impressão de teste.`, 'success');
        } catch (e) {
          aviso(`Não foi possível corrigir a densidade da etiqueta ${r.numero}.\n${e.message}`, 'error');
          button.disabled = false;
          button.textContent = 'Corrigir densidade';
        }
      });
      action.append(button);
    });
  };
})();
